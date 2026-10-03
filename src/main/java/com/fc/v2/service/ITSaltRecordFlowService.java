package com.fc.v2.service;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.model.auto.TSaltRecordFlow;

/**
 * 跨省经营备案单 Service接口（state-machine 形状：单据流转，不提供增删改查全套）
 *
 * 步次只有此处一处作答：走到哪一步、下一步放不放行、封卷后再递接不接、
 * 同一步重复递上来的第二遍另不另记——四句都从一个推进方法里取回话。
 *
 * @author fuce
 * @date 2026-09-14
 */
public interface ITSaltRecordFlowService {

    /** 按主键查询单据（读步次的老方法：原参原返，签名不动） */
    TSaltRecordFlow selectTSaltRecordFlowById(Long id);

    /** 列表查询（流转台账） */
    List<TSaltRecordFlow> selectTSaltRecordFlowList(QueryWrapper<TSaltRecordFlow> queryWrapper);

    /** 推进一档：老签名保留，等同 advance(id, null, remark) */
    TSaltRecordFlow advance(Long id, String remark);

    /**
     * 推进一档（重载，挂在一旁，不另立门户）。
     * reportedStage 是递单方报上来的步次，只当意图看待，不给越过当步的权限；
     * 纸面号与系统数出来的号不一致时，以数出来的为准入库、按数出来的回读。
     * 返回推进后的单据；被封口的单再递、或已到末档，返回 null；
     * 同一步重复递上来的第二遍，返回原单原样（不另记一笔）。
     */
    TSaltRecordFlow advance(Long id, Integer reportedStage, String remark);

    /** 回退一档：返回更新后的单据；已停在头一档或已收口的，被拒返回 null */
    TSaltRecordFlow rollback(Long id, String remark);

    /** 修改内容：已收口不得再改，被拒返回 false */
    boolean updateContent(Long id, String remark);

    /** 删除单据：已收口不得删除，被拒返回 false */
    boolean remove(Long id);

    /**
     * 在办备案单张数：立案加一、封卷减一。
     * 屏上那个数与当场一步步点出来的那个数，都从这一次计算里出，全系统只此一种算法。
     */
    long countActive();
}
