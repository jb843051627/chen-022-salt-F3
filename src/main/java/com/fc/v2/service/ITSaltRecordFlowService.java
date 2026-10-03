package com.fc.v2.service;

import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.model.auto.TSaltRecordFlow;
import com.fc.v2.model.auto.TSaltRecordNote;

/**
 * 跨省经营备案单 Service接口（state-machine 形状：单据流转，不提供增删改查全套）
 *
 * 步次只有这一处作答：当前走到哪一步、下一步放不放行、封卷后再递接不接、
 * 同一步第二遍另不另记一笔，一律看 {@link #advance(Long, Integer, String)} 的回话；
 * 接口不预判，页面不自带步次。表单报来的步次只算来意，与系统数出来的对不上时按数出来的办。
 *
 * @author fuce
 * @date 2026-09-14
 */
public interface ITSaltRecordFlowService {

    /** 按主键查询单据（读步次的老方法：原参原返，签名不动） */
    TSaltRecordFlow selectTSaltRecordFlowById(Long id);

    /** 列表查询（流转台账） */
    List<TSaltRecordFlow> selectTSaltRecordFlowList(QueryWrapper<TSaltRecordFlow> queryWrapper);

    /** 立案递单（递单受理）：四样齐才立，一企只养一张活单（在办/止住都占籍）；被拒返回 null */
    TSaltRecordFlow accept(TSaltRecordFlow draft);

    /**
     * 推进一档（推进姿势的老签名，原样保留；换姿势在它旁边挂重载，不另立门户）。
     * 等价于不报来意的 {@link #advance(Long, Integer, String)}。
     */
    TSaltRecordFlow advance(Long id, String remark);

    /**
     * 唯一的往前挪口。步次由系统按已经过的档次数出来，requestStage 只当意图：
     * <ul>
     *   <li>来意正是当前档：守本档闸口（核验笔齐 / 公示钟点连满 / 回执落），只放进紧挨着的后一步；</li>
     *   <li>来意是刚走过的头一档：同一步第二遍，幂等受下，原话不沉、不另记一段，原样回单；</li>
     *   <li>来意越过当步、或封了卷再递、或闸口没开：被拒返回 null。</li>
     * </ul>
     */
    TSaltRecordFlow advance(Long id, Integer requestStage, String remark);

    /** 回退一档：一次一档，头一档不再退；退回来那档原话沉底，重走另起一趟重新攒；封卷单不退 */
    TSaltRecordFlow rollback(Long id, String remark);

    /** 收口：closeType 取 N不必备案 / S就地止住；两说都算封口，封后改不动删不掉挪不了 */
    TSaltRecordFlow close(Long id, String closeType, String remark);

    /** 核验笔落一笔（材料核验档才收）；当笔已落齐不重复记 */
    boolean fillCheckItem(Long flowId, String itemNo, String itemName);

    /** 旧证缴回回执落纸（守在缴回封卷前） */
    boolean giveReceipt(Long id);

    /** 修改当前档留的话：封了口一字改不动，被拒返回 false */
    boolean updateContent(Long id, String remark);

    /** 删除单据：封了口整张删不掉（未封口按软删除处置），被拒返回 false */
    boolean remove(Long id);

    /** 段记载按段次正序取全（倒着数回递单受理就从这里数） */
    List<TSaltRecordNote> selectNotes(Long flowId);

    /** 倒着复核：段数、段次连续性、首尾档与受理簿那几笔合不合，逐段也经得起单看 */
    Map<String, Object> auditReplay(Long flowId);

    /** 在办的备案单张数：立案加一、封卷/收口减一（屏上的数与当场点出来的数同一口径） */
    int activeCount();
}
