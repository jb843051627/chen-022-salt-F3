package com.fc.v2.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fc.v2.mapper.auto.TSaltRecordCheckMapper;
import com.fc.v2.mapper.auto.TSaltRecordFlowMapper;
import com.fc.v2.mapper.auto.TSaltRecordNoteMapper;
import com.fc.v2.model.auto.TSaltRecordCheck;
import com.fc.v2.model.auto.TSaltRecordFlow;
import com.fc.v2.model.auto.TSaltRecordNote;
import com.fc.v2.service.ITSaltRecordFlowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

/**
 * 跨省经营备案单状态机服务单测。
 * 三个 Mapper 用 Mockito 接成内存账本：步次怎么数、闸口怎么守、段怎么落，全在服务层那一个推进口里见分晓。
 */
class TSaltRecordFlowServiceImplTest {

    private ITSaltRecordFlowService service;

    private final Map<Long, TSaltRecordFlow> flows = new LinkedHashMap<Long, TSaltRecordFlow>();
    private final Map<Long, TSaltRecordCheck> checks = new LinkedHashMap<Long, TSaltRecordCheck>();
    private final Map<Long, TSaltRecordNote> notes = new LinkedHashMap<Long, TSaltRecordNote>();
    private final AtomicLong idGen = new AtomicLong(1000L);

    @BeforeEach
    void setUp() {
        TSaltRecordFlowServiceImpl impl = new TSaltRecordFlowServiceImpl();
        ReflectionTestUtils.setField(impl, "saltRecordFlowMapper", flowMapper());
        ReflectionTestUtils.setField(impl, "saltRecordCheckMapper", checkMapper());
        ReflectionTestUtils.setField(impl, "saltRecordNoteMapper", noteMapper());
        service = impl;
    }

    // -------------------------------------------------- 立案：四样齐

    @Test
    void accept_fourRequired_missingAny_rejected() {
        TSaltRecordFlow base = draft("E1", 2);
        base.setEntNo(null);
        assertNull(service.accept(base), "缺企业代号立不起来");

        base = draft("E1", 2);
        base.setDepartAt(null);
        assertNull(service.accept(base), "缺起运那一刻立不起来");

        base = draft("E1", 2);
        base.setArriveAt(null);
        assertNull(service.accept(base), "缺到货那一刻立不起来");

        base = draft("E1", 2);
        base.setRegionCount(null);
        assertNull(service.accept(base), "缺县域个数立不起来");

        base = draft("E1", 0);
        assertNull(service.accept(base), "县域个数为零立不起来");

        base = draft("E1", 2);
        base.setArriveAt(new Date(base.getDepartAt().getTime() - 1000L));
        assertNull(service.accept(base), "到货早于起运立不起来");
    }

    @Test
    void accept_success_stageCountedFromZero_andOneNote() {
        TSaltRecordFlow f = service.accept(draft("E1", 3));
        assertNotNull(f);
        assertEquals(0, f.getStage().intValue(), "步次从递单受理（0）数起，不由受理岗手点");
        assertEquals(1, f.getStatus().intValue());
        assertEquals(3, f.getCheckRequired().intValue());
        assertEquals(1, service.activeCount(), "立案加一");
        assertEquals(1, service.selectNotes(f.getId()).size());
        assertEquals("accept", service.selectNotes(f.getId()).get(0).getAction());
    }

    // -------------------------------------------------- 一企一活单

    @Test
    void oneLiveBillPerEnterprise_activeOrClosed_blocksSecond() {
        assertNotNull(service.accept(draft("E1", 1)));
        assertNull(service.accept(draft("E1", 1)), "头一张仍在办，第二张立不起来");

        TSaltRecordFlow e2 = service.accept(draft("E2", 1));
        TSaltRecordFlow stopped = service.close(e2.getId(), "S", "止住");
        assertEquals(3, stopped.getStatus().intValue(), "半道止住");
        assertNull(service.accept(draft("E2", 1)), "半道止住的也占籍，第二张立不起来");
    }

    @Test
    void sealedBillReleasesEnterprise_nextBillAccepted() {
        TSaltRecordFlow first = driveToSeal("E3");
        assertEquals(2, first.getStatus().intValue());
        assertEquals(0, service.activeCount(), "封卷减一");
        TSaltRecordFlow second = service.accept(draft("E3", 1));
        assertNotNull(second, "封了卷之后再递要接，同企业可立新单");
        assertEquals(1, service.activeCount());
    }

    // -------------------------------------------------- 唯一推进口：步次系统数，来意只是意图

    @Test
    void advance_onlyNextStageAllowed_formStageIsIntent() {
        TSaltRecordFlow f = service.accept(draft("E4", 2));
        Long id = f.getId();

        assertNull(service.advance(id, 5, "想蹦到生效确认"), "越过当步，没有这个权限");
        assertEquals(0, service.selectTSaltRecordFlowById(id).getStage().intValue(), "蹦步被拒，步次仍是0");

        // 同一步第二遍：卷面上留的还是头一遍那句，第二遍不另起一行
        TSaltRecordFlow repeat = service.advance(id, 0, "第二遍的话");
        assertNotNull(repeat);
        assertEquals(0, repeat.getStage().intValue());
        assertEquals("头一句", repeat.getContent(), "同档第二遍不覆盖头一遍那句");
        assertEquals(1, service.selectNotes(id).size(), "同一步第二遍不另记一段");

        // 老签名（原参原返旁的原方法）推进 0→1
        assertEquals(1, service.advance(id, "材料受理").getStage().intValue());

        // 闸口：核验笔没填全，1→2 进不去
        assertNull(service.advance(id, 2, "核验未齐"), "核验笔没填全，下一步放不下去");
        service.fillCheckItem(id, "C1", "资质");
        service.fillCheckItem(id, "C2", "仓单");
        assertNull(service.advance(id, 2, "还差一笔"));
        service.fillCheckItem(id, "C3", "票据");
        TSaltRecordFlow at2 = service.advance(id, 2, "核验齐了");
        assertEquals(2, at2.getStage().intValue());
        assertNotNull(at2.getPublicityStart(), "核验笔落齐那一刻自动起公示钟");

        // 钟点没走满，材料再齐、后一步（缴回封卷）开着也算没开
        assertNull(service.advance(id, 3, "公示刚起钟"), "公示钟点没连着走满，进不了下一步");
        backdate(id, 49);
        TSaltRecordFlow at3 = service.advance(id, 3, "公示满了");
        assertEquals(3, at3.getStage().intValue());

        // 第四步看回执：没落回执放不下去
        assertNull(service.advance(id, 4, "回执没到"));
        assertTrue(service.giveReceipt(id));
        TSaltRecordFlow sealed = service.advance(id, 4, "旧证缴回");
        assertEquals(2, sealed.getStatus().intValue(), "第四步走完即落档封存");
        assertEquals(3, sealed.getStage().intValue(), "单上步次停在3，落档是封存不是第五步");
    }

    @Test
    void sealedBill_everythingFrozen() {
        TSaltRecordFlow f = driveToSeal("E5");
        Long id = f.getId();
        int notesBefore = service.selectNotes(id).size();

        assertNull(service.advance(id, null, "封后再推进"), "封后挪不了");
        assertNull(service.advance(id, 4, "封后再递不接"), "封了卷之后再递不接推进");
        assertNull(service.rollback(id, "封后回退"), "封后往后挪也不许");
        assertNull(service.close(id, "N", "封后收口"), "封了口不能改口");
        assertFalse(service.updateContent(id, "改一个字"), "一个字改不动");
        assertFalse(service.remove(id), "整张删不掉");
        assertEquals(notesBefore, service.selectNotes(id).size());
    }

    @Test
    void close_twoSayings_sealAndFreeze() {
        TSaltRecordFlow f = service.accept(draft("E6", 1));
        assertNull(service.close(f.getId(), "X", "不是两说"), "两说之外的说法不收");
        TSaltRecordFlow closed = service.close(f.getId(), "N", "不必备案");
        assertEquals(3, closed.getStatus().intValue());
        assertEquals("N", closed.getCloseType());
        assertNull(service.close(f.getId(), "S", "改口"), "收了口不能再收口");
        assertNull(service.advance(f.getId(), null, "x"), "收口后挪不了");
        assertFalse(service.updateContent(f.getId(), "y"), "收口后改不动");
        assertFalse(service.remove(f.getId()), "收口后删不掉");
    }

    // -------------------------------------------------- 回退：一档、沉底、重走重攒、头档不退

    @Test
    void rollback_oneStep_saidSinks_rewalkRebuilds_headStops() {
        TSaltRecordFlow f = service.accept(draft("E7", 2));
        Long id = f.getId();
        assertNull(service.rollback(id, "头档往外收"), "已停在头一档的，不再往外收");

        service.advance(id, 1, "材料受理");
        service.fillCheckItem(id, "C1", "资质");
        service.fillCheckItem(id, "C2", "仓单");
        service.fillCheckItem(id, "C3", "票据");
        service.advance(id, 2, "核验齐了");
        assertEquals(2, service.selectTSaltRecordFlowById(id).getStage().intValue());

        TSaltRecordFlow back = service.rollback(id, "退回来补材料");
        assertEquals(1, back.getStage().intValue(), "一次只挪一档");
        assertNull(back.getPublicityStart(), "退回材料核验：公示打断，起钟清零");
        List<TSaltRecordCheck> items = checks.values().stream()
                .filter(c -> c.getFlowId().equals(id)).collect(Collectors.toList());
        assertTrue(items.stream().allMatch(c -> c.getFilled() == 0), "退回来那档核验笔重新攒");

        List<TSaltRecordNote> notes = service.selectNotes(id);
        assertEquals(4, notes.size());
        TSaltRecordNote sunk = notes.get(2);
        assertEquals("核验齐了", sunk.getSaid(), "退回前那一句不删，沉在段记载里");
        assertEquals(2, sunk.getStageNo().intValue());
        assertEquals(1, sunk.getWalkNo().intValue());
        TSaltRecordNote rbNote = notes.get(3);
        assertEquals("rollback", rbNote.getAction());
        assertEquals(1, rbNote.getStageNo().intValue(), "退到材料核验档");
        assertEquals(2, rbNote.getWalkNo().intValue(), "回到的档另起一趟，头一趟的话仍沉在底下");

        // 重走：核验笔重新落齐、起钟、再进 2——同档另起一趟，旧段仍在
        service.fillCheckItem(id, "C1", null);
        service.fillCheckItem(id, "C2", null);
        service.fillCheckItem(id, "C3", null);
        assertNotNull(service.selectTSaltRecordFlowById(id).getPublicityStart(), "重走核验齐，重新起钟");
        backdate(id, 49);
        assertEquals(2, service.advance(id, 2, "重走核验齐").getStage().intValue());

        // 再连退两档：2→1→0，不许跳；到 0 停住
        assertEquals(1, service.rollback(id, "r2").getStage().intValue());
        assertEquals(0, service.rollback(id, "r1").getStage().intValue());
        assertNull(service.rollback(id, "r0"));

        // 同档两段都留着，不并成一笔
        List<TSaltRecordNote> stage2Notes = service.selectNotes(id).stream()
                .filter(n -> n.getStageNo() == 2).collect(Collectors.toList());
        assertEquals(2, stage2Notes.size(), "头趟与重走各一段");
        assertEquals(1, stage2Notes.get(0).getWalkNo().intValue());
        assertEquals(2, stage2Notes.get(1).getWalkNo().intValue());
        // 段次连续，倒着数回合得拢
        List<TSaltRecordNote> all = service.selectNotes(id);
        for (int i = 0; i < all.size(); i++) {
            assertEquals(i + 1, all.get(i).getSeq().intValue(), "段次连续不断号");
        }
    }

    // -------------------------------------------------- 第四步回退：回执作废重落

    @Test
    void rollbackFromSealStage_receiptVoided_andGateRechecked() {
        TSaltRecordFlow f = service.accept(draft("E71", 2));
        Long id = f.getId();
        service.advance(id, 1, "进核验");
        service.fillCheckItem(id, "C1", null);
        service.fillCheckItem(id, "C2", null);
        service.fillCheckItem(id, "C3", null);
        service.advance(id, 2, "核验齐");
        backdate(id, 49);
        service.advance(id, 3, "公示满");
        service.giveReceipt(id);
        assertEquals(1, service.selectTSaltRecordFlowById(id).getCertReturned().intValue());

        TSaltRecordFlow back = service.rollback(id, "退回来补公示");
        assertEquals(2, back.getStage().intValue());
        assertEquals(0, back.getCertReturned().intValue(), "回执作不得数，重到缴回封卷前重新落");
        backdate(id, 49);
        assertEquals(3, service.advance(id, 3, "公示重走满").getStage().intValue());
        assertNull(service.advance(id, 4, "回执没落，后一步开着也算没开"));
        service.giveReceipt(id);
        assertEquals(2, service.advance(id, 4, "回执重落后落档").getStatus().intValue());
    }

    // -------------------------------------------------- 同秒两笔各记各的

    @Test
    void twoEventsSameInstant_recordedSeparately() {
        TSaltRecordFlow f1 = service.accept(draft("E8", 1));
        TSaltRecordFlow f2 = service.accept(draft("E9", 1));
        TSaltRecordNote n1 = service.selectNotes(f1.getId()).get(0);
        TSaltRecordNote n2 = service.selectNotes(f2.getId()).get(0);
        // 两条来路挤进同一秒钟（这里压成同一毫秒）：各记各的，不并成一笔
        n2.setEventMs(n1.getEventMs());
        assertEquals(n1.getEventMs(), n2.getEventMs());
        assertEquals(1, service.selectNotes(f1.getId()).size());
        assertEquals(1, service.selectNotes(f2.getId()).size());
        assertNotEquals(n1.getId(), n2.getId());
    }

    // -------------------------------------------------- 倒着数回递单受理

    @Test
    void auditReplay_fullFlow_segmentsTieOut() {
        TSaltRecordFlow f = driveToSeal("E10");
        Map<String, Object> report = service.auditReplay(f.getId());
        assertEquals(Boolean.TRUE, report.get("pass"), report.get("problems").toString());
        assertEquals(5, report.get("count"), "递单受理+三次推进+落档封存，共五段");
        assertEquals("accept", report.get("firstAction"));
        assertEquals("seal", report.get("lastAction"));
        assertEquals(3, report.get("replayStage"));
        assertEquals(0, report.get("activeCount"), "屏上的数与当场点算的是同一次口径");
    }

    @Test
    void auditReplay_detectsTamperedStage() {
        TSaltRecordFlow f = service.accept(draft("E11", 1));
        service.advance(f.getId(), 1, "进入核验");
        // 绕过服务层把单上步次拨乱（年末追查要能发现凭什么对不上）
        flows.get(f.getId()).setStage(3);
        Map<String, Object> report = service.auditReplay(f.getId());
        assertEquals(Boolean.FALSE, report.get("pass"));
        @SuppressWarnings("unchecked")
        List<String> problems = (List<String>) report.get("problems");
        assertTrue(problems.stream().anyMatch(p -> p.contains("步次")), problems.toString());
    }

    // -------------------------------------------------- 在办张数

    @Test
    void activeCount_upOnAccept_downOnSealAndClose() {
        TSaltRecordFlow a = service.accept(draft("E12", 1));
        service.accept(draft("E13", 1));
        service.accept(draft("E14", 1));
        assertEquals(3, service.activeCount());
        service.close(a.getId(), "S", "止住");
        assertEquals(2, service.activeCount(), "收口减一");
        long countedFromStore = flows.values().stream().filter(x -> x.getStatus() == 1 && x.getDelFlag() == 0).count();
        assertEquals(countedFromStore, service.activeCount(), "屏上的数与当场一步步点出来的数同一次算的");
    }

    // ================================================================

    private TSaltRecordFlow draft(String entNo, int regionCount) {
        TSaltRecordFlow d = new TSaltRecordFlow();
        d.setEntNo(entNo);
        d.setDepartAt(new Date(System.currentTimeMillis() - 3_600_000L));
        d.setArriveAt(new Date());
        d.setRegionCount(regionCount);
        d.setRemark("头一句");
        return d;
    }

    /** 走完四步落档封卷的完整一单 */
    private TSaltRecordFlow driveToSeal(String entNo) {
        TSaltRecordFlow f = service.accept(draft(entNo, 2));
        Long id = f.getId();
        service.advance(id, 1, "进入材料核验");
        service.fillCheckItem(id, "C1", "资质");
        service.fillCheckItem(id, "C2", "仓单");
        service.fillCheckItem(id, "C3", "票据");
        service.advance(id, 2, "核验齐了");
        backdate(id, 49);
        service.advance(id, 3, "公示满了");
        service.giveReceipt(id);
        return service.advance(id, 4, "旧证缴回封卷");
    }

    private void backdate(Long id, int hoursAgo) {
        TSaltRecordFlow f = flows.get(id);
        f.setPublicityStart(new Date(System.currentTimeMillis() - hoursAgo * 3_600_000L));
    }

    // -------------------------------------------------- 内存账本 Mapper

    @SuppressWarnings("unchecked")
    private static List<Object> params(Wrapper<?> w) {
        // 占位符在 sqlSegment 文本里按 MPGENVAL1、2、3… 的先后出现（IN 也是逐位列开），照文本序取才不被 HashMap 搅乱
        com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> aw =
                (com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) w;
        Map<String, Object> map = aw.getParamNameValuePairs();
        List<Object> ordered = new ArrayList<Object>();
        java.util.regex.Matcher mt = java.util.regex.Pattern.compile("MPGENVAL\\d+")
                .matcher(w.getSqlSegment());
        while (mt.find()) {
            ordered.add(map.get(mt.group()));
        }
        return ordered;
    }

    private TSaltRecordFlowMapper flowMapper() {
        TSaltRecordFlowMapper m = mock(TSaltRecordFlowMapper.class);
        Answer<Object> ans = new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock inv) {
                String name = inv.getMethod().getName();
                Object[] args = inv.getArguments();
                if ("insert".equals(name)) {
                    TSaltRecordFlow e = (TSaltRecordFlow) args[0];
                    if (e.getId() == null) {
                        e.setId(idGen.incrementAndGet());
                    }
                    flows.put(e.getId(), e);
                    return 1;
                }
                if ("selectById".equals(name)) {
                    return flows.get(((Number) args[0]).longValue());
                }
                if ("updateById".equals(name)) {
                    TSaltRecordFlow e = (TSaltRecordFlow) args[0];
                    flows.put(e.getId(), e);
                    return 1;
                }
                if ("deleteById".equals(name)) {
                    return flows.remove(((Number) args[0]).longValue()) == null ? 0 : 1;
                }
                if ("selectOne".equals(name)) {
                    Wrapper<?> w = (Wrapper<?>) args[0];
                    Long id = params(w).stream().filter(v -> v instanceof Long).map(v -> (Long) v).findFirst().orElse(null);
                    return id == null ? null : flows.get(id);
                }
                if ("selectCount".equals(name)) {
                    Wrapper<?> w = (Wrapper<?>) args[0];
                    List<Object> v = new ArrayList<Object>(params(w));
                    String seg = w.getSqlSegment();
                    int c;
                    if (seg.contains("ent_no")) {
                        String ent = (String) v.get(0);
                        c = (int) flows.values().stream()
                                .filter(f -> ent.equals(f.getEntNo())
                                        && (f.getStatus() == 1 || f.getStatus() == 3)
                                        && f.getDelFlag() == 0).count();
                    } else {
                        int status = ((Number) v.get(0)).intValue();
                        c = (int) flows.values().stream()
                                .filter(f -> f.getStatus() == status && f.getDelFlag() == 0).count();
                    }
                    return Integer.valueOf(c);
                }
                if ("selectList".equals(name)) {
                    return new ArrayList<TSaltRecordFlow>(flows.values());
                }
                if ("delete".equals(name)) {
                    Wrapper<?> w = (Wrapper<?>) args[0];
                    Long flowId = (Long) params(w).iterator().next();
                    flows.remove(flowId);
                    return 1;
                }
                return defaultValue(inv.getMethod().getReturnType());
            }
        };
        org.mockito.Mockito.doAnswer(ans).when(m).insert(any());
        org.mockito.Mockito.doAnswer(ans).when(m).selectById(any());
        org.mockito.Mockito.doAnswer(ans).when(m).updateById(any());
        org.mockito.Mockito.doAnswer(ans).when(m).deleteById(any());
        org.mockito.Mockito.doAnswer(ans).when(m).selectOne(any());
        org.mockito.Mockito.doAnswer(ans).when(m).selectCount(any());
        org.mockito.Mockito.doAnswer(ans).when(m).selectList(any());
        org.mockito.Mockito.doAnswer(ans).when(m).delete(any());
        return m;
    }

    private TSaltRecordCheckMapper checkMapper() {
        TSaltRecordCheckMapper m = mock(TSaltRecordCheckMapper.class);
        Answer<Object> ans = new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock inv) {
                String name = inv.getMethod().getName();
                Object[] args = inv.getArguments();
                if ("insert".equals(name)) {
                    TSaltRecordCheck e = (TSaltRecordCheck) args[0];
                    if (e.getId() == null) {
                        e.setId(idGen.incrementAndGet());
                    }
                    checks.put(e.getId(), e);
                    return 1;
                }
                if ("updateById".equals(name)) {
                    TSaltRecordCheck e = (TSaltRecordCheck) args[0];
                    checks.put(e.getId(), e);
                    return 1;
                }
                if ("selectOne".equals(name)) {
                    Wrapper<?> w = (Wrapper<?>) args[0];
                    List<Object> v = new ArrayList<Object>(params(w));
                    Long flowId = (Long) v.get(0);
                    String itemNo = (String) v.get(1);
                    return checks.values().stream()
                            .filter(c -> c.getFlowId().equals(flowId) && itemNo.equals(c.getItemNo())
                                    && c.getDelFlag() == 0).findFirst().orElse(null);
                }
                if ("selectCount".equals(name)) {
                    Wrapper<?> w = (Wrapper<?>) args[0];
                    Long flowId = (Long) params(w).iterator().next();
                    long c = checks.values().stream()
                            .filter(x -> x.getFlowId().equals(flowId) && x.getFilled() == 1 && x.getDelFlag() == 0).count();
                    return Integer.valueOf((int) c);
                }
                if ("selectList".equals(name)) {
                    Wrapper<?> w = (Wrapper<?>) args[0];
                    Long flowId = (Long) params(w).iterator().next();
                    return checks.values().stream().filter(c -> c.getFlowId().equals(flowId) && c.getDelFlag() == 0)
                            .collect(Collectors.toList());
                }
                if ("delete".equals(name)) {
                    Wrapper<?> w = (Wrapper<?>) args[0];
                    Long flowId = (Long) params(w).iterator().next();
                    checks.values().removeIf(c -> c.getFlowId().equals(flowId));
                    return 1;
                }
                return defaultValue(inv.getMethod().getReturnType());
            }
        };
        org.mockito.Mockito.doAnswer(ans).when(m).insert(any());
        org.mockito.Mockito.doAnswer(ans).when(m).updateById(any());
        org.mockito.Mockito.doAnswer(ans).when(m).selectOne(any());
        org.mockito.Mockito.doAnswer(ans).when(m).selectCount(any());
        org.mockito.Mockito.doAnswer(ans).when(m).selectList(any());
        org.mockito.Mockito.doAnswer(ans).when(m).delete(any());
        return m;
    }

    private TSaltRecordNoteMapper noteMapper() {
        TSaltRecordNoteMapper m = mock(TSaltRecordNoteMapper.class);
        Answer<Object> ans = new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock inv) {
                String name = inv.getMethod().getName();
                Object[] args = inv.getArguments();
                if ("insert".equals(name)) {
                    TSaltRecordNote e = (TSaltRecordNote) args[0];
                    if (e.getId() == null) {
                        e.setId(idGen.incrementAndGet());
                    }
                    notes.put(e.getId(), e);
                    return 1;
                }
                if ("selectById".equals(name)) {
                    return notes.get(((Number) args[0]).longValue());
                }
                if ("selectCount".equals(name)) {
                    Wrapper<?> w = (Wrapper<?>) args[0];
                    List<Object> v = new ArrayList<Object>(params(w));
                    Long flowId = (Long) v.get(0);
                    long c;
                    if (w.getSqlSegment().contains("stage_no")) {
                        int stageNo = ((Number) v.get(1)).intValue();
                        c = notes.values().stream()
                                .filter(n -> n.getFlowId().equals(flowId) && n.getStageNo() == stageNo
                                        && n.getDelFlag() == 0).count();
                    } else {
                        c = notes.values().stream()
                                .filter(n -> n.getFlowId().equals(flowId) && n.getDelFlag() == 0).count();
                    }
                    return Integer.valueOf((int) c);
                }
                if ("selectList".equals(name)) {
                    Wrapper<?> w = (Wrapper<?>) args[0];
                    Long flowId = (Long) params(w).iterator().next();
                    return notes.values().stream()
                            .filter(n -> n.getFlowId().equals(flowId) && n.getDelFlag() == 0)
                            .sorted(Comparator.comparing(TSaltRecordNote::getSeq))
                            .collect(Collectors.toList());
                }
                if ("delete".equals(name)) {
                    Wrapper<?> w = (Wrapper<?>) args[0];
                    Long flowId = (Long) params(w).iterator().next();
                    notes.values().removeIf(n -> n.getFlowId().equals(flowId));
                    return 1;
                }
                return defaultValue(inv.getMethod().getReturnType());
            }
        };
        org.mockito.Mockito.doAnswer(ans).when(m).insert(any());
        org.mockito.Mockito.doAnswer(ans).when(m).selectById(any());
        org.mockito.Mockito.doAnswer(ans).when(m).selectCount(any());
        org.mockito.Mockito.doAnswer(ans).when(m).selectList(any());
        org.mockito.Mockito.doAnswer(ans).when(m).delete(any());
        return m;
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }
}
