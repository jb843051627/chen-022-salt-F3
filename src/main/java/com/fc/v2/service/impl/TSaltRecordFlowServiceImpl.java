package com.fc.v2.service.impl;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TSaltRecordFlowMapper;
import com.fc.v2.model.auto.TSaltRecordFlow;
import com.fc.v2.service.ITSaltRecordFlowService;

/**
 * 跨省经营备案单 Service业务层处理（state-machine 形状：单据流转）
 *
 * 四步排定：0递单受理 / 1材料核验 / 2生效确认 / 3缴回封卷。
 * 动作只两种：往前挪一档、往后收一档；单上的步次由系统按已经过的步数数出来，
 * 递单方报上来的步次只当意图看待。收了口（已封卷，含不必备案、就地止住）的单：
 * 一个字改不动、整张删不掉、前后都不许再挪。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TSaltRecordFlowServiceImpl implements ITSaltRecordFlowService {

    /** 末档步次：缴回封卷 */
    private static final int MAX_STAGE = 3;
    /** 0未起 */
    private static final int STATUS_NOT_STARTED = 0;
    /** 1在办 */
    private static final int STATUS_ACTIVE = 1;
    /** 2已封卷（收口；不必备案、就地止住同此） */
    private static final int STATUS_TERMINAL = 2;

    /** 一段一记的分段记号 */
    private static final String SEG_SEP = "；";

    @javax.annotation.Resource
    private TSaltRecordFlowMapper saltRecordFlowMapper;

    @Override
    public TSaltRecordFlow selectTSaltRecordFlowById(Long id) {
        return this.saltRecordFlowMapper.selectById(id);
    }

    @Override
    public List<TSaltRecordFlow> selectTSaltRecordFlowList(QueryWrapper<TSaltRecordFlow> queryWrapper) {
        return this.saltRecordFlowMapper.selectList(queryWrapper);
    }

    @Override
    public TSaltRecordFlow advance(Long id, String remark) {
        return advance(id, null, remark);
    }

    @Override
    public TSaltRecordFlow advance(Long id, Integer reportedStage, String remark) {
        TSaltRecordFlow r = this.saltRecordFlowMapper.selectById(id);
        if (r == null) {
            return null;
        }
        if (isTerminal(r)) {
            // 封了卷之后再递：不接
            return null;
        }
        int stage = r.getStage() == null ? 0 : r.getStage();
        if (reportedStage != null && reportedStage <= stage) {
            // 同一步第二遍递上来：卷面上留的还是头一遍那句，第二遍不另起一行
            return r;
        }
        if (stage >= MAX_STAGE) {
            // 末档之外没有可进的一步（正常不会走到这：进末档即封卷）
            return null;
        }
        // 只许进紧挨着的后一档；纸面写的号与数出来的号不一致时，以数出来的为准
        int next = stage + 1;
        r.setStage(next);
        r.setStatus(next >= MAX_STAGE ? STATUS_TERMINAL : STATUS_ACTIVE);
        r.setLastAction(remark);
        appendSegment(r, next, remark);
        this.saltRecordFlowMapper.updateById(r);
        return r;
    }

    @Override
    public TSaltRecordFlow rollback(Long id, String remark) {
        TSaltRecordFlow r = this.saltRecordFlowMapper.selectById(id);
        if (r == null) {
            return null;
        }
        if (isTerminal(r)) {
            // 收了口：事后挪一挪也不许
            return null;
        }
        int stage = r.getStage() == null ? 0 : r.getStage();
        if (stage <= 0) {
            // 已经停在头一档的，不再往外收
            return null;
        }
        // 一次只挪一档；退回来那一档原先写的话沉到最底下（分段留存，不删不改），
        // 重走时从这一档重新攒（再推进时另起新段）
        r.setStage(stage - 1);
        r.setStatus(STATUS_ACTIVE);
        r.setLastAction(remark);
        this.saltRecordFlowMapper.updateById(r);
        return r;
    }

    @Override
    public boolean updateContent(Long id, String remark) {
        TSaltRecordFlow r = this.saltRecordFlowMapper.selectById(id);
        if (r == null) {
            return false;
        }
        if (isTerminal(r)) {
            // 收了口：一个字改不动
            return false;
        }
        r.setContent(remark);
        return this.saltRecordFlowMapper.updateById(r) > 0;
    }

    @Override
    public boolean remove(Long id) {
        TSaltRecordFlow r = this.saltRecordFlowMapper.selectById(id);
        if (r == null) {
            return false;
        }
        if (isTerminal(r)) {
            // 收了口：整张删不掉
            return false;
        }
        return this.saltRecordFlowMapper.deleteById(id) > 0;
    }

    @Override
    public long countActive() {
        // 在办张数只此一处算法：立案加一、封卷减一，屏上数与点验数同一次算出
        QueryWrapper<TSaltRecordFlow> qw = new QueryWrapper<TSaltRecordFlow>();
        qw.eq("status", STATUS_ACTIVE).eq("del_flag", 0);
        return this.saltRecordFlowMapper.selectCount(qw);
    }

    /** 是否已收口（已封卷，含不必备案、就地止住） */
    private boolean isTerminal(TSaltRecordFlow r) {
        return r.getStatus() != null && r.getStatus() == STATUS_TERMINAL;
    }

    /**
     * 一段一记：哪一步写了什么话、落在哪一刻，按落笔先后往尾上接。
     * 两条来路挤进同一秒钟也各记各的——分段只按落笔次序，从不按时刻合并。
     */
    private void appendSegment(TSaltRecordFlow r, int stage, String remark) {
        String at = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        String seg = "S" + stage + " " + at + " " + (remark == null ? "" : remark);
        String old = r.getContent();
        r.setContent(old == null || old.isEmpty() ? seg : old + SEG_SEP + seg);
    }

}
