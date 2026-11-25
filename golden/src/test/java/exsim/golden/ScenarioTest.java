package exsim.golden;

import static exsim.core.model.CancelReason.END_OF_DAY;
import static exsim.core.model.CancelReason.IOC_REMAINDER;
import static exsim.core.model.CancelReason.MASS_CANCEL;
import static exsim.core.model.CancelReason.REPLACED_TO_ZERO;
import static exsim.core.model.CancelReason.REQUESTED;
import static exsim.core.model.CancelReason.SELF_TRADE;
import static exsim.core.model.OrdType.IOC;
import static exsim.core.model.OrdType.LIMIT;
import static exsim.core.model.OrdType.MARKET;
import static exsim.core.model.Side.BUY;
import static exsim.core.model.Side.SELL;
import static exsim.golden.Fixtures.cancel;
import static exsim.golden.Fixtures.ioc;
import static exsim.golden.Fixtures.last;
import static exsim.golden.Fixtures.limit;
import static exsim.golden.Fixtures.limitAcct;
import static exsim.golden.Fixtures.market;
import static exsim.golden.Fixtures.oid;
import static exsim.golden.Fixtures.only;
import static exsim.golden.Fixtures.px;
import static exsim.golden.Fixtures.replace;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import exsim.core.MatchingEngine;
import exsim.core.model.InputEvent.EndOfDay;
import exsim.core.model.InputEvent.Halt;
import exsim.core.model.InputEvent.MassCancel;
import exsim.core.model.InputEvent.Resume;
import exsim.core.model.Output;
import exsim.core.model.Output.Accepted;
import exsim.core.model.Output.BookDigest;
import exsim.core.model.Output.CancelRejected;
import exsim.core.model.Output.Cancelled;
import exsim.core.model.Output.DuplicateIgnored;
import exsim.core.model.Output.Fill;
import exsim.core.model.Output.MdAdd;
import exsim.core.model.Output.MdDelete;
import exsim.core.model.Output.MdExecute;
import exsim.core.model.Output.MdHalt;
import exsim.core.model.Output.MdReduce;
import exsim.core.model.Output.Rejected;
import exsim.core.model.Output.Replaced;
import exsim.core.model.Reject;
import exsim.core.model.SymbolDigest;
import exsim.core.state.BookDigests;
import exsim.core.state.StateView;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Spec scenarios from spec/matching.md, section 9. Every scenario runs on the real
 * engine and the golden model at once (see {@link Dual}), so a passing scenario also
 * proves the two agree on outputs, chain and full state after every event.
 */
class ScenarioTest {

    private static List<Long> aggressorPrices(List<Output> outs) {
        return only(outs, Fill.class).stream().filter(Fill::aggressor).map(Fill::price).toList();
    }

    // ---- Resting and crossing ----------------------------------------------------

    @Test
    void s01_limitOrderRests() {
        Dual d = new Dual();
        assertEquals(List.of(
                new Accepted(1, 1, oid(0), "AAA", BUY, LIMIT, 100, px("100.00")),
                new MdAdd("AAA", 1, oid(0), BUY, px("100.00"), 100)),
                d.send(limit(1, 1, "AAA", BUY, 100, "100.00")));
    }

    @Test
    void s02_crossingOrderTradesAtRestingPrice() {
        Dual d = new Dual();
        d.send(limit(1, 1, "AAA", BUY, 100, "100.00"));
        assertEquals(List.of(
                new Accepted(2, 1, oid(1), "AAA", SELL, LIMIT, 100, px("99.00")),
                new Fill(1, 1, oid(0), "AAA", BUY, px("100.00"), 100, 0, false, 1),
                new Fill(2, 1, oid(1), "AAA", SELL, px("100.00"), 100, 0, true, 1),
                new MdExecute("AAA", 2, oid(0), px("100.00"), 100, 1)),
                d.send(limit(2, 1, "AAA", SELL, 100, "99.00")));
        assertTrue(d.engine().stateView().orders().isEmpty());
    }

    @Test
    void s03_partialFillRemainderRests() {
        Dual d = new Dual();
        d.send(limit(1, 1, "AAA", BUY, 100, "100.00"));
        List<Output> outs = d.send(limit(2, 1, "AAA", SELL, 150, "100.00"));
        assertEquals(new MdAdd("AAA", 3, oid(1), SELL, px("100.00"), 50), last(outs));
    }

    @Test
    void s04_sweepsLevelsBestPriceFirst() {
        Dual d = new Dual();
        d.send(limit(2, 1, "AAA", SELL, 50, "100.03"));
        d.send(limit(3, 1, "AAA", SELL, 50, "100.01"));
        d.send(limit(4, 1, "AAA", SELL, 50, "100.02"));
        List<Output> outs = d.send(limit(1, 1, "AAA", BUY, 120, "100.02"));
        assertEquals(List.of(px("100.01"), px("100.02")), aggressorPrices(outs));
        assertEquals(new MdAdd("AAA", 6, oid(3), BUY, px("100.02"), 20), last(outs));
    }

    @Test
    void s05_timePriorityWithinALevel() {
        Dual d = new Dual();
        d.send(limit(2, 1, "AAA", SELL, 50, "100.00"));
        d.send(limit(3, 1, "AAA", SELL, 50, "100.00"));
        Fill passive = only(d.send(limit(1, 1, "AAA", BUY, 50, "100.00")), Fill.class).get(0);
        assertEquals(2, passive.sessionId());
    }

    // ---- IOC and market orders -----------------------------------------------------

    @Test
    void s06_iocRemainderIsCancelled() {
        Dual d = new Dual();
        d.send(limit(2, 1, "AAA", SELL, 30, "100.00"));
        List<Output> outs = d.send(ioc(1, 1, "AAA", BUY, 100, "100.00"));
        assertEquals(new Cancelled(1, 1, 0, oid(1), 70, IOC_REMAINDER), last(outs));
        assertTrue(only(outs, MdAdd.class).isEmpty());
    }

    @Test
    void s07_iocThatDoesNotCrossIsCancelledInFull() {
        Dual d = new Dual();
        assertEquals(List.of(
                new Accepted(1, 1, oid(0), "AAA", BUY, IOC, 10, px("100.00")),
                new Cancelled(1, 1, 0, oid(0), 10, IOC_REMAINDER)),
                d.send(ioc(1, 1, "AAA", BUY, 10, "100.00")));
    }

    @Test
    void s08_marketOrderStopsAtCollar() {
        Dual d = new Dual();
        d.send(limit(2, 1, "AAA", SELL, 10, "100.00"));
        d.send(limit(3, 1, "AAA", SELL, 10, "104.00"));
        d.send(limit(4, 1, "AAA", SELL, 10, "106.00"));
        List<Output> outs = d.send(market(1, 1, "AAA", BUY, 30));
        assertEquals(new Accepted(1, 1, oid(3), "AAA", BUY, MARKET, 30, px("105.00")), outs.get(0));
        assertEquals(List.of(px("100.00"), px("104.00")), aggressorPrices(outs));
        assertEquals(new Cancelled(1, 1, 0, oid(3), 10, IOC_REMAINDER), last(outs));
    }

    @Test
    void s09_marketOrderWithEmptyOppositeSideIsRejected() {
        Dual d = new Dual();
        d.send(limit(2, 1, "AAA", BUY, 10, "100.00"));
        assertEquals(List.of(new Rejected(1, 1, Reject.NO_LIQUIDITY)), d.send(market(1, 1, "AAA", BUY, 10)));
    }

    @Test
    void s10_collarRoundsToTick() {
        Dual d = new Dual();
        d.send(limit(2, 1, "PNY", BUY, 5, "0.5003"));
        d.send(limit(3, 1, "PNY", BUY, 5, "0.4752"));
        List<Output> sell = d.send(market(1, 1, "PNY", SELL, 10));
        // ceil(0.5003 * 0.95) = 0.4753, so the 0.4752 bid is outside the collar.
        assertEquals(px("0.4753"), ((Accepted) sell.get(0)).price());
        assertEquals(List.of(px("0.5003")), aggressorPrices(sell));
        assertEquals(new Cancelled(1, 1, 0, oid(2), 5, IOC_REMAINDER), last(sell));

        d.send(limit(4, 1, "AAA", SELL, 1, "100.01"));
        // floor(100.01 * 1.05) = 105.0105, rounded down to the $0.01 tick = 105.01.
        List<Output> buy = d.send(market(1, 2, "AAA", BUY, 1));
        assertEquals(px("105.01"), ((Accepted) buy.get(0)).price());
    }

    // ---- Validation ----------------------------------------------------------------

    @Test
    void s11_pricesMustBeOnTick() {
        Dual d = new Dual();
        assertEquals(List.of(new Rejected(1, 1, Reject.INVALID_PRICE)), d.send(limit(1, 1, "AAA", BUY, 10, "100.005")));
        assertEquals(List.of(new Rejected(1, 2, Reject.INVALID_PRICE)), d.send(limit(1, 2, "AAA", BUY, 10, "0")));
        assertEquals(List.of(new Rejected(1, 3, Reject.INVALID_PRICE)), d.send(limit(1, 3, "AAA", BUY, 10, "1.0001")));
    }

    @Test
    void s12_subDollarPricesUseTheSmallTick() {
        Dual d = new Dual();
        assertInstanceOf(Accepted.class, d.send(limit(1, 1, "PNY", BUY, 10, "0.5001")).get(0));
        assertInstanceOf(Accepted.class, d.send(limit(1, 2, "AAA", BUY, 10, "0.9999")).get(0));
        assertInstanceOf(Accepted.class, d.send(limit(1, 3, "AAA", BUY, 10, "1.00")).get(0));
    }

    @Test
    void s13_quantityMustBePositive() {
        Dual d = new Dual();
        assertEquals(List.of(new Rejected(1, 1, Reject.INVALID_QTY)), d.send(limit(1, 1, "AAA", BUY, 0, "100.00")));
        assertEquals(List.of(new Rejected(1, 2, Reject.INVALID_QTY)), d.send(limit(1, 2, "AAA", BUY, -5, "100.00")));
    }

    // ---- Cancel --------------------------------------------------------------------

    @Test
    void s14_cancelRemovesRestingOrder() {
        Dual d = new Dual();
        d.send(limit(1, 1, "AAA", BUY, 100, "100.00"));
        assertEquals(List.of(
                new Cancelled(1, 1, 2, oid(0), 100, REQUESTED),
                new MdDelete("AAA", 2, oid(0))),
                d.send(cancel(1, 2, 1)));
        assertTrue(only(d.send(limit(2, 1, "AAA", SELL, 100, "100.00")), Fill.class).isEmpty());
    }

    @Test
    void s15_cancelOfUnknownOrderIsRejected() {
        Dual d = new Dual();
        assertEquals(List.of(new CancelRejected(1, 1, 99, Reject.UNKNOWN_ORDER)), d.send(cancel(1, 1, 99)));
    }

    @Test
    void s16_cancelAfterFullFillIsRejected() {
        Dual d = new Dual();
        d.send(limit(1, 1, "AAA", BUY, 100, "100.00"));
        d.send(limit(2, 1, "AAA", SELL, 100, "100.00"));
        assertEquals(List.of(new CancelRejected(1, 2, 1, Reject.UNKNOWN_ORDER)), d.send(cancel(1, 2, 1)));
    }

    // ---- Cancel-replace --------------------------------------------------------------

    @Test
    void s17_reducingQuantityKeepsPriority() {
        Dual d = new Dual();
        d.send(limit(2, 1, "AAA", SELL, 100, "100.00"));
        d.send(limit(3, 1, "AAA", SELL, 100, "100.00"));
        assertEquals(List.of(
                new Replaced(2, 2, 1, oid(0), 60, px("100.00"), 60, true),
                new MdReduce("AAA", 3, oid(0), 60)),
                d.send(replace(2, 2, 1, 60, "100.00")));
        Fill passive = only(d.send(limit(1, 1, "AAA", BUY, 50, "100.00")), Fill.class).get(0);
        assertEquals(2, passive.sessionId());
        assertEquals(2, passive.clOrdId());
    }

    @Test
    void s36_replaceWithSameQuantityAndPriceKeepsPriority() {
        Dual d = new Dual();
        d.send(limit(2, 1, "AAA", SELL, 100, "100.00"));
        d.send(limit(3, 1, "AAA", SELL, 100, "100.00"));
        assertEquals(List.of(new Replaced(2, 2, 1, oid(0), 100, px("100.00"), 100, true)),
                d.send(replace(2, 2, 1, 100, "100.00")));
        Fill passive = only(d.send(limit(1, 1, "AAA", BUY, 50, "100.00")), Fill.class).get(0);
        assertEquals(2, passive.sessionId());
    }

    @Test
    void s18_increasingQuantityLosesPriority() {
        Dual d = new Dual();
        d.send(limit(2, 1, "AAA", SELL, 100, "100.00"));
        d.send(limit(3, 1, "AAA", SELL, 100, "100.00"));
        assertEquals(List.of(
                new Replaced(2, 2, 1, oid(0), 150, px("100.00"), 150, false),
                new MdDelete("AAA", 3, oid(0)),
                new MdAdd("AAA", 4, oid(0), SELL, px("100.00"), 150)),
                d.send(replace(2, 2, 1, 150, "100.00")));
        Fill passive = only(d.send(limit(1, 1, "AAA", BUY, 50, "100.00")), Fill.class).get(0);
        assertEquals(3, passive.sessionId());
    }

    @Test
    void s19_priceChangeThatCrossesTradesImmediately() {
        Dual d = new Dual();
        d.send(limit(1, 1, "AAA", BUY, 100, "99.00"));
        d.send(limit(2, 1, "AAA", SELL, 100, "101.00"));
        assertEquals(List.of(
                new Replaced(2, 2, 1, oid(1), 100, px("99.00"), 100, false),
                new MdDelete("AAA", 3, oid(1)),
                new Fill(1, 1, oid(0), "AAA", BUY, px("99.00"), 100, 0, false, 1),
                new Fill(2, 2, oid(1), "AAA", SELL, px("99.00"), 100, 0, true, 1),
                new MdExecute("AAA", 4, oid(0), px("99.00"), 100, 1)),
                d.send(replace(2, 2, 1, 100, "99.00")));
    }

    @Test
    void s20_replaceToFilledQuantityCancels() {
        Dual d = new Dual();
        d.send(limit(2, 1, "AAA", SELL, 100, "100.00"));
        d.send(limit(1, 1, "AAA", BUY, 40, "100.00"));
        assertEquals(List.of(
                new Cancelled(2, 1, 2, oid(0), 60, REPLACED_TO_ZERO),
                new MdDelete("AAA", 3, oid(0))),
                d.send(replace(2, 2, 1, 40, "100.00")));
    }

    @Test
    void s21_replaceGivesTheOrderANewClOrdId() {
        Dual d = new Dual();
        d.send(limit(1, 1, "AAA", BUY, 100, "100.00"));
        d.send(replace(1, 2, 1, 50, "100.00"));
        assertEquals(List.of(new CancelRejected(1, 3, 1, Reject.UNKNOWN_ORDER)), d.send(cancel(1, 3, 1)));
        assertEquals(new Cancelled(1, 2, 4, oid(0), 50, REQUESTED), d.send(cancel(1, 4, 2)).get(0));
    }

    @Test
    void s22_replaceWhileHaltedIsRejected() {
        Dual d = new Dual();
        d.send(limit(1, 1, "AAA", BUY, 10, "100.00"));
        d.send(new Halt("AAA"));
        assertEquals(List.of(new CancelRejected(1, 2, 1, Reject.HALTED)), d.send(replace(1, 2, 1, 5, "100.00")));
    }

    // ---- Duplicate detection ---------------------------------------------------------

    @Test
    void s23_repeatedClOrdIdIsIgnored() {
        Dual d = new Dual();
        d.send(limit(1, 5, "AAA", BUY, 100, "100.00"));
        StateView before = d.engine().stateView();
        assertEquals(List.of(new DuplicateIgnored(1, 5)), d.send(limit(1, 5, "AAA", BUY, 100, "100.00")));
        assertEquals(before, d.engine().stateView());
    }

    @Test
    void s24_lowerClOrdIdIsADuplicate() {
        Dual d = new Dual();
        d.send(limit(1, 5, "AAA", BUY, 100, "100.00"));
        assertEquals(List.of(new DuplicateIgnored(1, 3)), d.send(limit(1, 3, "AAA", BUY, 100, "100.00")));
        assertEquals(List.of(new DuplicateIgnored(1, 4)), d.send(cancel(1, 4, 5)));
    }

    @Test
    void s25_highWaterMarksArePerSession() {
        Dual d = new Dual();
        d.send(limit(1, 5, "AAA", BUY, 100, "100.00"));
        assertInstanceOf(Accepted.class, d.send(limit(2, 1, "AAA", BUY, 100, "100.00")).get(0));
    }

    @Test
    void s26_rejectedMessageStillAdvancesTheMark() {
        Dual d = new Dual();
        d.send(limit(1, 1, "AAA", BUY, 0, "100.00"));
        assertEquals(List.of(new DuplicateIgnored(1, 1)), d.send(limit(1, 1, "AAA", BUY, 10, "100.00")));
    }

    // ---- Self-trade prevention -------------------------------------------------------

    @Test
    void s27_selfTradeCancelsTheAggressor() {
        Dual d = new Dual();
        d.send(limitAcct(1, 1, 7, "AAA", BUY, 100, "100.00"));
        assertEquals(List.of(
                new Accepted(2, 1, oid(1), "AAA", SELL, LIMIT, 50, px("100.00")),
                new Cancelled(2, 1, 0, oid(1), 50, SELF_TRADE)),
                d.send(limitAcct(2, 1, 7, "AAA", SELL, 50, "100.00")));
        assertEquals(100, d.engine().stateView().orders().get(0).leaves());
    }

    @Test
    void s28_selfTradeAfterPartialFills() {
        Dual d = new Dual();
        d.send(limitAcct(2, 1, 5, "AAA", SELL, 30, "100.00"));
        d.send(limitAcct(3, 1, 7, "AAA", SELL, 30, "100.01"));
        List<Output> outs = d.send(limitAcct(1, 1, 7, "AAA", BUY, 100, "100.01"));
        assertEquals(List.of(px("100.00")), aggressorPrices(outs));
        assertEquals(new Cancelled(1, 1, 0, oid(2), 70, SELF_TRADE), last(outs));
        assertEquals(1, d.engine().stateView().orders().size());
    }

    // ---- Halts and control messages --------------------------------------------------

    @Test
    void s29_haltBlocksNewOrdersButAllowsCancels() {
        Dual d = new Dual();
        d.send(limit(1, 1, "AAA", BUY, 10, "100.00"));
        assertEquals(List.of(new MdHalt("AAA", 2, true)), d.send(new Halt("AAA")));
        assertEquals(List.of(new Rejected(1, 2, Reject.HALTED)), d.send(limit(1, 2, "AAA", BUY, 10, "100.00")));
        assertEquals(List.of(new Rejected(2, 1, Reject.HALTED)), d.send(market(2, 1, "AAA", SELL, 10)));
        assertEquals(List.of(
                new Cancelled(1, 1, 3, oid(0), 10, REQUESTED),
                new MdDelete("AAA", 3, oid(0))),
                d.send(cancel(1, 3, 1)));
        assertEquals(List.of(new MdHalt("AAA", 4, false)), d.send(new Resume("AAA")));
        assertInstanceOf(Accepted.class, d.send(limit(1, 4, "AAA", BUY, 10, "100.00")).get(0));
    }

    @Test
    void s30_massCancelByAccountUsesCanonicalOrder() {
        Dual d = new Dual();
        d.send(limitAcct(1, 1, 1, "BBB", BUY, 10, "25.00"));
        d.send(limitAcct(1, 2, 1, "AAA", SELL, 10, "101.00"));
        d.send(limitAcct(1, 3, 1, "AAA", BUY, 10, "99.00"));
        d.send(limitAcct(1, 4, 1, "AAA", BUY, 10, "99.50"));
        d.send(limitAcct(2, 1, 2, "AAA", BUY, 10, "99.20"));
        List<Output> outs = d.send(new MassCancel(MassCancel.Scope.ACCOUNT, 1));
        List<Cancelled> cancels = only(outs, Cancelled.class);
        assertEquals(List.of(oid(3), oid(2), oid(1), oid(0)), cancels.stream().map(Cancelled::orderId).toList());
        assertTrue(cancels.stream().allMatch(c -> c.reason() == MASS_CANCEL));
        assertEquals(List.of(oid(4)), d.engine().stateView().orders().stream().map(o -> o.orderId()).toList());
    }

    @Test
    void s31_massCancelBySessionAndAll() {
        Dual d = new Dual();
        d.send(limitAcct(1, 1, 9, "AAA", BUY, 10, "99.00"));
        d.send(limitAcct(2, 1, 9, "AAA", BUY, 10, "98.00"));
        List<Cancelled> bySession = only(d.send(new MassCancel(MassCancel.Scope.SESSION, 2)), Cancelled.class);
        assertEquals(List.of(oid(1)), bySession.stream().map(Cancelled::orderId).toList());
        assertEquals(1, only(d.send(new MassCancel(MassCancel.Scope.ALL, 0)), Cancelled.class).size());
        assertEquals(List.of(), d.send(new MassCancel(MassCancel.Scope.ALL, 0)));
    }

    @Test
    void s32_endOfDayDigestsCancelsAndCloses() {
        Dual d = new Dual();
        d.send(limit(1, 1, "AAA", BUY, 10, "99.00"));
        d.send(limit(2, 1, "BBB", SELL, 10, "25.00"));
        List<Output> outs = d.send(new EndOfDay());
        BookDigest digest = assertInstanceOf(BookDigest.class, outs.get(0));
        assertEquals(List.of("AAA", "BBB"), digest.symbols().stream().map(SymbolDigest::symbol).toList());
        assertEquals(BookDigests.shardDigest(digest.symbols()), digest.shardDigest());
        List<Cancelled> cancels = only(outs, Cancelled.class);
        assertEquals(2, cancels.size());
        assertTrue(cancels.stream().allMatch(c -> c.reason() == END_OF_DAY));
        assertTrue(d.engine().stateView().orders().isEmpty());

        assertEquals(List.of(new Rejected(1, 2, Reject.MARKET_CLOSED)), d.send(limit(1, 2, "AAA", BUY, 10, "99.00")));
        assertEquals(List.of(new CancelRejected(1, 3, 1, Reject.MARKET_CLOSED)), d.send(cancel(1, 3, 1)));
        assertEquals(List.of(new CancelRejected(1, 4, 1, Reject.MARKET_CLOSED)), d.send(replace(1, 4, 1, 5, "99.00")));
        assertEquals(List.of(), d.send(new EndOfDay()));
    }

    // ---- Identifiers, sequences, digests ---------------------------------------------

    @Test
    void s33_marketDataSequencesArePerSymbol() {
        Dual d = new Dual();
        assertEquals(1, only(d.send(limit(1, 1, "AAA", BUY, 10, "99.00")), MdAdd.class).get(0).seq());
        assertEquals(1, only(d.send(limit(1, 2, "BBB", BUY, 10, "25.00")), MdAdd.class).get(0).seq());
        assertEquals(2, only(d.send(limit(1, 3, "AAA", BUY, 10, "98.00")), MdAdd.class).get(0).seq());
    }

    @Test
    void s34_orderIdPacksShardAndOffset() {
        var order = limit(1, 1, "AAA", BUY, 10, "99.00");
        Accepted real = (Accepted) new MatchingEngine(3).apply(5, order).get(0);
        Accepted reference = (Accepted) new GoldenEngine(3).apply(5, order).get(0);
        assertEquals(0x0300_0000_0000_0005L, real.orderId());
        assertEquals(real, reference);
    }

    @Test
    void s35_bookDigestUsesOnlyFeedVisibleFields() {
        Dual a = new Dual();
        Dual b = new Dual();
        a.send(limitAcct(1, 1, 1, "AAA", BUY, 10, "99.00"));
        b.send(limitAcct(7, 4, 9, "AAA", BUY, 10, "99.00"));
        assertEquals(a.engine().bookDigests(), b.engine().bookDigests());
        assertNotEquals(a.engine().stateDigest(), b.engine().stateDigest());
    }
}
