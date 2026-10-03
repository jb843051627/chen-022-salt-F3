package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TSaltRecordCheckMapper;
import com.fc.v2.mapper.auto.TSaltRecordFlowMapper;
import com.fc.v2.mapper.auto.TSaltRecordNoteMapper;
import com.fc.v2.model.auto.TSaltRecordCheck;
import com.fc.v2.model.auto.TSaltRecordFlow;
import com.fc.v2.model.auto.TSaltRecordNote;
import com.fc.v2.service.ITSaltRecordFlowService;
import com.fc.v2.util.SnowflakeIdWorker;

/**
 * 跨省经营备案单 Service业务层处理（state-machine 形状：单据流转）
 *
 * 步次只有 {@link #advance(Long, Integer, String)} 这一处作答：系统按已经过的档次数 stage，
 * 表单报来的 requestStage 只当意图——同档第二遍幂等受下不另记，越过当步/封卷再递一律拒绝。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TSaltRecordFlowServiceImpl implements ITSaltRecordFlowService {

    /** 四步：0递单受理 1材料核验 2生效确认 3缴回封卷 */
    private static final int STAGE_FIRST = 0;
    private static final int STAGE_CHECK = 1;
    private static final int STAGE_PUBLICITY = 2;
    private static final int STAGE_SEAL = 3;
    /** 落档封存段在段记载里单占一个步次号 */
    private static final int STAGE_NOTE_SEAL = 4;

    /** 备案单情形 */
    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_SEALED = 2;
    private static final int STATUS_CLOSED = 3;

    /** 收口两说 */
    public static final String CLOSE_NO_NEED = "N";
    public static final String CLOSE_STOP_HERE = "S";

    /** 当地定的默认应填核验笔数、默认连着公示的钟点数 */
    private static final int DEFAULT_CHECK_REQUIRED = 3;
    private static final BigDecimal DEFAULT_PUBLICITY_HOURS = new BigDecimal("48");

    private static final String ACT_ADVANCE = "advance";
    private static final String ACT_ROLLBACK = "rollback";
    private static final String ACT_CLOSE = "close";
    private static final String ACT_SEAL = "seal";
    private static final String ACT_ACCEPT = "accept";

    @javax.annotation.Resource
    private TSaltRecordFlowMapper saltRecordFlowMapper;

    @javax.annotation.Resource
    private TSaltRecordCheckMapper saltRecordCheckMapper;

    @javax.annotation.Resource
    private TSaltRecordNoteMapper saltRecordNoteMapper;

    @Override
    public TSaltRecordFlow selectTSaltRecordFlowById(Long id) {
        return this.saltRecordFlowMapper.selectById(id);
    }

    @Override
    public List<TSaltRecordFlow> selectTSaltRecordFlowList(QueryWrapper<TSaltRecordFlow> queryWrapper) {
        return this.saltRecordFlowMapper.selectList(queryWrapper);
    }

    /**
     * 立案递单：四样齐（企业代号、起运那一刻、到货那一刻、拟销县域个数）才立；
     * 一家企业名下同时只许养一张活单——在办或止住的，第二张立不起来（库内唯一键兜底）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TSaltRecordFlow accept(TSaltRecordFlow draft) {
        if (draft == null
                || isBlank(draft.getEntNo())
                || draft.getDepartAt() == null
                || draft.getArriveAt() == null
                || draft.getRegionCount() == null || draft.getRegionCount().intValue() <= 0
                || draft.getArriveAt().before(draft.getDepartAt())) {
            return null;
        }
        Integer live = this.saltRecordFlowMapper.selectCount(new QueryWrapper<TSaltRecordFlow>()
                .eq("ent_no", draft.getEntNo())
                .in("status", STATUS_ACTIVE, STATUS_CLOSED)
                .eq("del_flag", 0));
        if (live != null && live.intValue() > 0) {
            return null;
        }

        if (isBlank(draft.getBizNo())) {
            draft.setBizNo("BA" + SnowflakeIdWorker.getUUID());
        }
        draft.setId(null);
        draft.setStage(STAGE_FIRST);
        draft.setStatus(STATUS_ACTIVE);
        draft.setCloseType(null);
        draft.setCertReturned(0);
        draft.setPublicityStart(null);
        draft.setPublicityDoneHours(null);
        if (draft.getCheckRequired() == null || draft.getCheckRequired() <= 0) {
            draft.setCheckRequired(DEFAULT_CHECK_REQUIRED);
        }
        if (draft.getPublicityHours() == null || draft.getPublicityHours().signum() <= 0) {
            draft.setPublicityHours(DEFAULT_PUBLICITY_HOURS);
        }
        draft.setDelFlag(0);
        draft.setContent(draft.getRemark());
        draft.setLastAction(ACT_ACCEPT);
        try {
            this.saltRecordFlowMapper.insert(draft);
        } catch (DuplicateKeyException e) {
            // 活单唯一键（在办/止住）撞了：第二张立不起来
            return null;
        }
        appendNote(draft.getId(), STAGE_FIRST, ACT_ACCEPT, draft.getRemark());
        return draft;
    }

    /** 老签名原参原返：不报来意，按当前档的正当后一步推进 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TSaltRecordFlow advance(Long id, String remark) {
        return advance(id, null, remark);
    }

    /**
     * 唯一的往前挪口。行锁取单，系统按已经过的档次数回话：
     * 来意=后一步且闸口开，才放进紧挨着的一步；来意=本步，按第二遍幂等受下，不挪不另记；
     * 越过当步、封卷再递、闸口没开（钟点没走满等），都回 null。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TSaltRecordFlow advance(Long id, Integer requestStage, String remark) {
        TSaltRecordFlow r = lockFlow(id);
        if (r == null || !Integer.valueOf(STATUS_ACTIVE).equals(r.getStatus())) {
            return null;
        }
        int stage = r.getStage() == null ? STAGE_FIRST : r.getStage();
        if (requestStage != null) {
            if (requestStage.intValue() == stage) {
                // 同一步第二遍：卷面上还是头一遍那句，不另起一行，原样回单
                return r;
            }
            if (requestStage.intValue() != stage + 1) {
                // 报来的步次只当意图：越过当步，没有这个权限；落在身后的旧步也不接
                return null;
            }
        }
        if (!gateOpen(r, stage)) {
            return null;
        }

        if (stage >= STAGE_SEAL) {
            // 第四步回执落了：缴回封卷，落档封存
            r.setStatus(STATUS_SEALED);
            r.setCloseType(null);
            r.setLastAction(ACT_SEAL);
            if (remark != null) {
                r.setContent(remark);
            }
            this.saltRecordFlowMapper.updateById(r);
            appendNote(r.getId(), STAGE_NOTE_SEAL, ACT_SEAL, remark);
            return r;
        }

        r.setStage(stage + 1);
        r.setStatus(STATUS_ACTIVE);
        r.setLastAction(ACT_ADVANCE);
        r.setContent(remark);
        this.saltRecordFlowMapper.updateById(r);
        appendNote(r.getId(), stage + 1, ACT_ADVANCE, remark);
        return r;
    }

    /**
     * 回退一档：一次只挪一档；已在头一档不再往外收；封了口不许挪。
     * 退回那档原先写的话沉到段记载最底下，卷面上清空，重走从这一档重新攒。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TSaltRecordFlow rollback(Long id, String remark) {
        TSaltRecordFlow r = lockFlow(id);
        if (r == null || !Integer.valueOf(STATUS_ACTIVE).equals(r.getStatus())) {
            return null;
        }
        int stage = r.getStage() == null ? STAGE_FIRST : r.getStage();
        if (stage <= STAGE_FIRST) {
            return null;
        }
        int back = stage - 1;
        r.setStage(back);
        r.setStatus(STATUS_ACTIVE);
        r.setLastAction(ACT_ROLLBACK);
        // 原话随上一段沉底（appendNote 记的是退回动作与本句），卷面清空重攒
        r.setContent(remark);
        if (back == STAGE_CHECK) {
            // 退回材料核验：核验笔重新攒，公示打断，起钟清零
            resetCheckItems(r.getId());
            r.setPublicityStart(null);
            r.setPublicityDoneHours(null);
        } else if (back == STAGE_PUBLICITY) {
            // 退回生效确认：公示重新连着走；第四步的回执也作不得数，重到档前重新落
            r.setPublicityStart(new Date());
            r.setPublicityDoneHours(BigDecimal.ZERO);
            r.setCertReturned(0);
        }
        this.saltRecordFlowMapper.updateById(r);
        appendNote(r.getId(), back, ACT_ROLLBACK, remark);
        return r;
    }

    /** 收口："不必备案" N、"就地止住" S 两说都算收了口，封住 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TSaltRecordFlow close(Long id, String closeType, String remark) {
        if (!CLOSE_NO_NEED.equals(closeType) && !CLOSE_STOP_HERE.equals(closeType)) {
            return null;
        }
        TSaltRecordFlow r = lockFlow(id);
        if (r == null || !Integer.valueOf(STATUS_ACTIVE).equals(r.getStatus())) {
            return null;
        }
        int stage = r.getStage() == null ? STAGE_FIRST : r.getStage();
        r.setStatus(STATUS_CLOSED);
        r.setCloseType(closeType);
        r.setLastAction(ACT_CLOSE);
        r.setContent(remark);
        this.saltRecordFlowMapper.updateById(r);
        appendNote(r.getId(), stage, ACT_CLOSE, remark);
        return r;
    }

    /**
     * 核验笔落一笔（坐在材料核验档才收）；这一笔已落齐不重复记。
     * 最后一笔落齐那一刻，自动起公示钟。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean fillCheckItem(Long flowId, String itemNo, String itemName) {
        if (flowId == null || isBlank(itemNo)) {
            return false;
        }
        TSaltRecordFlow r = lockFlow(flowId);
        if (r == null || !Integer.valueOf(STATUS_ACTIVE).equals(r.getStatus())
                || !Integer.valueOf(STAGE_CHECK).equals(r.getStage())) {
            return false;
        }
        TSaltRecordCheck item = this.saltRecordCheckMapper.selectOne(new QueryWrapper<TSaltRecordCheck>()
                .eq("flow_id", flowId).eq("item_no", itemNo).eq("del_flag", 0));
        if (item == null) {
            item = new TSaltRecordCheck();
            item.setFlowId(flowId);
            item.setItemNo(itemNo);
            item.setItemName(itemName);
            item.setFilled(1);
            item.setFillAt(new Date());
            item.setDelFlag(0);
            this.saltRecordCheckMapper.insert(item);
        } else if (item.getFilled() == null || item.getFilled() == 0) {
            item.setFilled(1);
            item.setFillAt(new Date());
            if (itemName != null) {
                item.setItemName(itemName);
            }
            this.saltRecordCheckMapper.updateById(item);
        } else {
            return true;
        }

        int required = r.getCheckRequired() == null || r.getCheckRequired() <= 0
                ? DEFAULT_CHECK_REQUIRED : r.getCheckRequired();
        Integer filled = this.saltRecordCheckMapper.selectCount(new QueryWrapper<TSaltRecordCheck>()
                .eq("flow_id", flowId).eq("filled", 1).eq("del_flag", 0));
        if (filled != null && filled >= required && r.getPublicityStart() == null) {
            Date now = new Date();
            r.setPublicityStart(now);
            r.setPublicityDoneHours(BigDecimal.ZERO);
            this.saltRecordFlowMapper.updateById(r);
        }
        return true;
    }

    /** 旧证缴回回执落纸 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean giveReceipt(Long id) {
        TSaltRecordFlow r = lockFlow(id);
        if (r == null || !Integer.valueOf(STATUS_ACTIVE).equals(r.getStatus())) {
            return false;
        }
        if (Integer.valueOf(1).equals(r.getCertReturned())) {
            return true;
        }
        r.setCertReturned(1);
        return this.saltRecordFlowMapper.updateById(r) > 0;
    }

    /** 修改当前档留的话：收了口一个字改不动 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateContent(Long id, String remark) {
        TSaltRecordFlow r = this.saltRecordFlowMapper.selectById(id);
        if (r == null || isSealed(r)) {
            return false;
        }
        r.setContent(remark);
        return this.saltRecordFlowMapper.updateById(r) > 0;
    }

    /** 删除单据：收了口整张删不掉；未封口连同核验笔、段记载一并移除 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean remove(Long id) {
        TSaltRecordFlow r = this.saltRecordFlowMapper.selectById(id);
        if (r == null || isSealed(r)) {
            return false;
        }
        this.saltRecordCheckMapper.delete(new QueryWrapper<TSaltRecordCheck>().eq("flow_id", id));
        this.saltRecordNoteMapper.delete(new QueryWrapper<TSaltRecordNote>().eq("flow_id", id));
        return this.saltRecordFlowMapper.deleteById(id) > 0;
    }

    @Override
    public List<TSaltRecordNote> selectNotes(Long flowId) {
        return this.saltRecordNoteMapper.selectList(new QueryWrapper<TSaltRecordNote>()
                .eq("flow_id", flowId).eq("del_flag", 0).orderByAsc("seq"));
    }

    /**
     * 倒着复核：从缴回封卷/收口倒着数回递单受理，段数、段次连续性、首尾动作要合得拢；
     * 再照段记载把步次当场重走一遍，得数与单上的 stage/status 对得上。随便抽一段也单独取得出。
     */
    @Override
    public Map<String, Object> auditReplay(Long flowId) {
        Map<String, Object> out = new HashMap<String, Object>();
        TSaltRecordFlow flow = this.saltRecordFlowMapper.selectById(flowId);
        List<TSaltRecordNote> notes = selectNotes(flowId);
        List<String> problems = new ArrayList<String>();
        if (flow == null) {
            problems.add("单不存在");
            out.put("pass", false);
            out.put("problems", problems);
            return out;
        }

        int n = notes.size();
        for (int i = 0; i < n; i++) {
            TSaltRecordNote note = notes.get(i);
            if (note.getSeq() == null || note.getSeq().intValue() != i + 1) {
                problems.add("第" + (i + 1) + "段段次断号：库里 seq=" + note.getSeq());
            }
            // 随便抽一段单看：按主键复核，回读必须就是这一段
            TSaltRecordNote picked = this.saltRecordNoteMapper.selectById(note.getId());
            if (picked == null || !picked.getFlowId().equals(flowId)) {
                problems.add("第" + (i + 1) + "段经不起单看");
            }
        }

        int replayStage = -1;
        String replayStatus = null;
        if (n > 0) {
            TSaltRecordNote first = notes.get(0);
            if (!Integer.valueOf(STAGE_FIRST).equals(first.getStageNo()) || !ACT_ACCEPT.equals(first.getAction())) {
                problems.add("头一笔不是递单受理");
            }
            replayStage = STAGE_FIRST;
            for (int i = 1; i < n; i++) {
                TSaltRecordNote note = notes.get(i);
                int to = note.getStageNo() == null ? -1 : note.getStageNo();
                String act = note.getAction();
                if (ACT_ADVANCE.equals(act)) {
                    if (to != replayStage + 1) {
                        problems.add("第" + (i + 1) + "段推进不是紧挨着的后一步");
                    }
                    replayStage = to;
                } else if (ACT_ROLLBACK.equals(act)) {
                    if (to != replayStage - 1) {
                        problems.add("第" + (i + 1) + "段回退不止一档/错档");
                    }
                    replayStage = to;
                } else if (ACT_CLOSE.equals(act)) {
                    if (to != replayStage) {
                        problems.add("收口段不在止住当时所在档");
                    }
                    replayStatus = ACT_CLOSE;
                } else if (ACT_SEAL.equals(act)) {
                    if (to != STAGE_NOTE_SEAL || replayStage != STAGE_SEAL) {
                        problems.add("落档封存段对不上第四步");
                    }
                    replayStatus = ACT_SEAL;
                } else {
                    problems.add("第" + (i + 1) + "段动作不识：" + act);
                }
            }
        } else {
            problems.add("一笔记载都没有");
        }

        if (ACT_SEAL.equals(replayStatus)) {
            if (!Integer.valueOf(STATUS_SEALED).equals(flow.getStatus())) {
                problems.add("段记载已落档，单上情形却不是封卷落档");
            }
            if (replayStage != STAGE_SEAL || !Integer.valueOf(STAGE_SEAL).equals(flow.getStage())) {
                problems.add("倒着数回来的步次与第四步合不拢");
            }
        } else if (ACT_CLOSE.equals(replayStatus)) {
            if (!Integer.valueOf(STATUS_CLOSED).equals(flow.getStatus())) {
                problems.add("段记载已收口，单上情形却不是止住");
            }
            if (flow.getStage() == null || replayStage != flow.getStage().intValue()) {
                problems.add("收口时所在档与单上步次合不拢");
            }
        } else {
            if (!Integer.valueOf(STATUS_ACTIVE).equals(flow.getStatus())) {
                problems.add("末段没有收口/封存，单却不在办");
            }
            if (flow.getStage() == null || replayStage != flow.getStage().intValue()) {
                problems.add("倒着数回来的步次(" + replayStage + ")与单上(" + flow.getStage() + ")合不拢");
            }
        }

        // 在办数当场点一遍，与单上情形同一口径
        int active = activeCount();
        out.put("activeCount", active);
        out.put("count", n);
        out.put("firstAction", n > 0 ? notes.get(0).getAction() : null);
        out.put("lastAction", n > 0 ? notes.get(n - 1).getAction() : null);
        out.put("replayStage", replayStage);
        out.put("pass", problems.isEmpty());
        out.put("problems", problems);
        return out;
    }

    /** 在办的备案单张数：立案加一、封卷/收口减一，就这一个口径 */
    @Override
    public int activeCount() {
        Integer c = this.saltRecordFlowMapper.selectCount(new QueryWrapper<TSaltRecordFlow>()
                .eq("status", STATUS_ACTIVE).eq("del_flag", 0));
        return c == null ? 0 : c.intValue();
    }

    // ------------------------------------------------------------------

    /** 行锁取单：两条来路同时挤进来，也排作先后，不各算各的 */
    private TSaltRecordFlow lockFlow(Long id) {
        if (id == null) {
            return null;
        }
        return this.saltRecordFlowMapper.selectOne(new QueryWrapper<TSaltRecordFlow>()
                .eq("id", id).eq("del_flag", 0).last("FOR UPDATE"));
    }

    /** 各档闸口：第二步核验笔填全、第三步公示钟点连着走满、第四步回执落 */
    private boolean gateOpen(TSaltRecordFlow r, int stage) {
        if (stage == STAGE_CHECK) {
            int required = r.getCheckRequired() == null || r.getCheckRequired() <= 0
                    ? DEFAULT_CHECK_REQUIRED : r.getCheckRequired();
            Integer filled = this.saltRecordCheckMapper.selectCount(new QueryWrapper<TSaltRecordCheck>()
                    .eq("flow_id", r.getId()).eq("filled", 1).eq("del_flag", 0));
            return filled != null && filled >= required;
        }
        if (stage == STAGE_PUBLICITY) {
            if (r.getPublicityStart() == null) {
                return false;
            }
            BigDecimal need = r.getPublicityHours() == null || r.getPublicityHours().signum() <= 0
                    ? DEFAULT_PUBLICITY_HOURS : r.getPublicityHours();
            BigDecimal done = elapsedHours(r.getPublicityStart(), new Date());
            // 同一次算出已走钟点与闸口判定，写回单上
            r.setPublicityDoneHours(done);
            return done.compareTo(need) >= 0;
        }
        if (stage == STAGE_SEAL) {
            return Integer.valueOf(1).equals(r.getCertReturned());
        }
        return true;
    }

    private BigDecimal elapsedHours(Date from, Date to) {
        long ms = to.getTime() - from.getTime();
        return ms < 0 ? BigDecimal.ZERO : new BigDecimal(ms).divide(new BigDecimal(3600_000L), 2, BigDecimal.ROUND_HALF_UP);
    }

    private void resetCheckItems(Long flowId) {
        List<TSaltRecordCheck> items = this.saltRecordCheckMapper.selectList(new QueryWrapper<TSaltRecordCheck>()
                .eq("flow_id", flowId).eq("del_flag", 0));
        for (TSaltRecordCheck item : items) {
            item.setFilled(0);
            item.setFillAt(null);
            this.saltRecordCheckMapper.updateById(item);
        }
    }

    /** 落一段：段次本单连续递增，趟次按同档旧段沉底数 +1；毫秒时刻各记各的 */
    private void appendNote(Long flowId, int stageNo, String action, String said) {
        Integer before = this.saltRecordNoteMapper.selectCount(new QueryWrapper<TSaltRecordNote>()
                .eq("flow_id", flowId).eq("del_flag", 0));
        int seq = (before == null ? 0 : before) + 1;
        Integer sameStage = this.saltRecordNoteMapper.selectCount(new QueryWrapper<TSaltRecordNote>()
                .eq("flow_id", flowId).eq("stage_no", stageNo).eq("del_flag", 0));
        int walk = (sameStage == null ? 0 : sameStage) + 1;

        TSaltRecordNote note = new TSaltRecordNote();
        note.setFlowId(flowId);
        note.setStageNo(stageNo);
        note.setWalkNo(walk);
        note.setSeq(seq);
        note.setAction(action);
        note.setSaid(said);
        note.setEventMs(System.currentTimeMillis());
        note.setDelFlag(0);
        this.saltRecordNoteMapper.insert(note);
    }

    private boolean isSealed(TSaltRecordFlow r) {
        return Integer.valueOf(STATUS_SEALED).equals(r.getStatus())
                || Integer.valueOf(STATUS_CLOSED).equals(r.getStatus());
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
