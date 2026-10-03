package com.fc.v2.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.common.base.BaseController;
import com.fc.v2.common.domain.AjaxResult;
import com.fc.v2.common.domain.ResultTable;
import com.fc.v2.common.log.Log;
import com.fc.v2.model.auto.TSaltRecordFlow;
import com.fc.v2.model.auto.TSaltRecordNote;
import com.fc.v2.service.ITSaltRecordFlowService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 跨省经营备案单 Controller（state-machine 形状：流转入口）
 *
 * 控制器不预判步次：走到哪一步、下一步放不放行、封卷再递接不接、第二遍另不另记，
 * 全听服务层那一个推进方法的回话；表单上的 requestStage 只是来意，原样转进去。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Api(value = "跨省经营备案单")
@Controller
@RequestMapping("/saltRecordFlow")
public class SaltRecordFlowController extends BaseController {

    private final String prefix = "admin/saltRecordFlow";

    @Autowired
    private ITSaltRecordFlowService saltRecordFlowService;

    @ApiOperation(value = "流转台账跳转", notes = "流转台账跳转")
    @GetMapping("/view")
    @RequiresPermissions("saltRecordFlow:view")
    public String view(ModelMap model) {
        return prefix + "/list";
    }

    @Log(title = "跨省经营备案单流转台账", action = "list")
    @ApiOperation(value = "流转台账", notes = "流转台账")
    @GetMapping("/list")
    @RequiresPermissions("saltRecordFlow:list")
    @ResponseBody
    public ResultTable list(TSaltRecordFlow record) {
        QueryWrapper<TSaltRecordFlow> queryWrapper = new QueryWrapper<TSaltRecordFlow>();
        queryWrapper.eq("del_flag", 0);
        if (record.getEntNo() != null && !record.getEntNo().trim().isEmpty()) {
            queryWrapper.like("ent_no", record.getEntNo().trim());
        }
        if (record.getBizNo() != null && !record.getBizNo().trim().isEmpty()) {
            queryWrapper.like("biz_no", record.getBizNo().trim());
        }
        if (record.getStatus() != null) {
            queryWrapper.eq("status", record.getStatus());
        }
        queryWrapper.orderByDesc("create_time");
        startPage();
        com.github.pagehelper.PageInfo<TSaltRecordFlow> page =
                new com.github.pagehelper.PageInfo<TSaltRecordFlow>(saltRecordFlowService.selectTSaltRecordFlowList(queryWrapper));
        return pageTable(page.getList(), page.getTotal());
    }

    @ApiOperation(value = "单据明细", notes = "步次按系统数出来的回读")
    @GetMapping("/get")
    @RequiresPermissions("saltRecordFlow:view")
    @ResponseBody
    public AjaxResult get(Long id) {
        TSaltRecordFlow flow = saltRecordFlowService.selectTSaltRecordFlowById(id);
        return flow == null ? AjaxResult.error("单不存在") : AjaxResult.success(200, "操作成功", flow);
    }

    @Log(title = "跨省经营备案单立案", action = "accept")
    @ApiOperation(value = "立案递单", notes = "四样齐才立，一企只养一张活单；requestStage 只当来意")
    @PostMapping("/accept")
    @RequiresPermissions("saltRecordFlow:accept")
    @ResponseBody
    public AjaxResult accept(TSaltRecordFlow draft) {
        TSaltRecordFlow saved = saltRecordFlowService.accept(draft);
        return saved == null
                ? AjaxResult.error("立案被拒：四样未齐，或该企业名下仍有在办/止住的活单")
                : AjaxResult.success(200, "立案受理", saved);
    }

    @Log(title = "跨省经营备案单推进", action = "advance")
    @ApiOperation(value = "往前挪一步", notes = "只许进紧挨着的后一步；来意步次对不上系统数出来的，按系统的回话办")
    @PostMapping("/advance")
    @RequiresPermissions("saltRecordFlow:advance")
    @ResponseBody
    public AjaxResult advance(Long id, Integer requestStage, String remark) {
        TSaltRecordFlow r = saltRecordFlowService.advance(id, requestStage, remark);
        return r == null ? AjaxResult.error("这一步放不下去：闸口未开、来意越过当步，或单已封口")
                : AjaxResult.success(200, "推进受理", r);
    }

    @Log(title = "跨省经营备案单回退", action = "rollback")
    @ApiOperation(value = "往后收一档", notes = "一次一档，头一档不再退；原话沉底，重走重新攒")
    @PostMapping("/rollback")
    @RequiresPermissions("saltRecordFlow:rollback")
    @ResponseBody
    public AjaxResult rollback(Long id, String remark) {
        TSaltRecordFlow r = saltRecordFlowService.rollback(id, remark);
        return r == null ? AjaxResult.error("退不回去：已在头一档，或单已封口")
                : AjaxResult.success(200, "回退受理", r);
    }

    @Log(title = "跨省经营备案单收口", action = "close")
    @ApiOperation(value = "收口", notes = "不必备案 N / 就地止住 S，两说都算封口")
    @PostMapping("/close")
    @RequiresPermissions("saltRecordFlow:close")
    @ResponseBody
    public AjaxResult close(Long id, String closeType, String remark) {
        TSaltRecordFlow r = saltRecordFlowService.close(id, closeType, remark);
        return r == null ? AjaxResult.error("收口被拒：说法不识、单不在办或已封口")
                : AjaxResult.success(200, "已收口封存", r);
    }

    @Log(title = "跨省经营备案单核验笔", action = "fillCheck")
    @ApiOperation(value = "核验笔落一笔", notes = "材料核验档才收；落齐那笔自动起公示钟")
    @PostMapping("/fillCheck")
    @RequiresPermissions("saltRecordFlow:advance")
    @ResponseBody
    public AjaxResult fillCheck(Long id, String itemNo, String itemName) {
        return saltRecordFlowService.fillCheckItem(id, itemNo, itemName)
                ? AjaxResult.success("核验笔已落")
                : AjaxResult.error("核验笔收不下：不在材料核验档，或笔号空着");
    }

    @Log(title = "跨省经营备案单回执", action = "receipt")
    @ApiOperation(value = "旧证缴回回执落纸", notes = "守在缴回封卷前")
    @PostMapping("/receipt")
    @RequiresPermissions("saltRecordFlow:advance")
    @ResponseBody
    public AjaxResult receipt(Long id) {
        return saltRecordFlowService.giveReceipt(id)
                ? AjaxResult.success("回执已落")
                : AjaxResult.error("回执落不下：单不在办");
    }

    @ApiOperation(value = "段记载", notes = "哪一步写了什么话、落在哪一刻，按段次正序")
    @GetMapping("/notes")
    @RequiresPermissions("saltRecordFlow:view")
    @ResponseBody
    public AjaxResult notes(Long id) {
        List<TSaltRecordNote> notes = saltRecordFlowService.selectNotes(id);
        return AjaxResult.success(200, "操作成功", notes);
    }

    @Log(title = "跨省经营备案单倒推复核", action = "audit")
    @ApiOperation(value = "倒着数回递单受理", notes = "段数、段次、首尾档与受理簿那几笔合不合")
    @GetMapping("/audit")
    @RequiresPermissions("saltRecordFlow:audit")
    @ResponseBody
    public AjaxResult audit(Long id) {
        Map<String, Object> report = saltRecordFlowService.auditReplay(id);
        return AjaxResult.success(200, "操作成功", report);
    }

    @ApiOperation(value = "在办张数", notes = "立案加一、封卷减一，与当场点算同一口径")
    @GetMapping("/activeCount")
    @RequiresPermissions("saltRecordFlow:list")
    @ResponseBody
    public AjaxResult activeCount() {
        return AjaxResult.success(200, "操作成功", saltRecordFlowService.activeCount());
    }
}
