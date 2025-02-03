package ca.yorku.cmg.lob.exchange;

import java.util.ArrayList;

import ca.yorku.cmg.lob.orderbook.*;
import ca.yorku.cmg.lob.security.*;
import ca.yorku.cmg.lob.trader.*;
import ca.yorku.cmg.lob.tradestandards.*;

public class Exchange {

    Orderbook book;
    SecurityList securities = new SecurityList();
    AccountsList accounts = new AccountsList();
    ArrayList<Trade> tradesLog = new ArrayList<>();
    long totalFees = 0;

    public Exchange() {
        book = new Orderbook();
    }

    public boolean validateOrder(IOrder o) {
        Security security = securities.getSecurityByTicker(o.getTicker());
        if (security == null) {
            System.err.println("Order validation: ticker " + o.getTicker() + " not supported.");
            return false;
        }

        Trader trader = accounts.getTraderByID(o.getTrader().getID());
        if (trader == null) {
            System.err.println("Order validation: trader with ID " + o.getTrader().getID() + " not registered with the exchange.");
            return false;
        }

        int pos = accounts.getTraderAccount(trader).getPosition(o.getTicker());
        long bal = accounts.getTraderAccount(trader).getBalance();

        if (o instanceof Ask && pos < o.getQuantity()) {
            System.err.println("Order validation: seller with ID " + trader.getID() + " not enough shares of " + o.getTicker() + ": has " + pos + " and tries to sell " + o.getQuantity());
            return false;
        }

        if (o instanceof Bid && bal < o.getValue()) {
            System.err.println("Order validation: buyer with ID " + trader.getID() + " does not have enough balance: has $" + bal / 100.0 + " and tries to buy for $" + o.getValue() / 100.0);
            return false;
        }

        return true;
    }

    public void submitOrder(IOrder o, long time) {
        if (!validateOrder(o)) {
            return;
        }

        OrderOutcome oOutcome = book.processOrder(o, time);
        if (oOutcome.getUnfulfilledOrder().getQuantity() > 0) {
            if (o instanceof Bid) {
                book.getBids().addOrder(oOutcome.getUnfulfilledOrder());
            } else {
                book.getAsks().addOrder(oOutcome.getUnfulfilledOrder());
            }
        }

        tradesLog.addAll(oOutcome.getResultingTrades());
        for (ITrade t : oOutcome.getResultingTrades()) {
            Trader buyer = t.getBuyer();
            Trader seller = t.getSeller();
            Account buyerAcc = accounts.getTraderAccount(buyer);
            Account sellerAcc = accounts.getTraderAccount(seller);

            long buyerFee = t.getBuyerFee();
            buyerAcc.updateBalance(-buyerFee);
            buyerAcc.updateBalance(-t.getTradeValue());
            buyerAcc.updatePosition(t.getSecurity().getTicker(), t.getQuantity());

            long sellerFee = t.getSellerFee();
            sellerAcc.updateBalance(-sellerFee);
            sellerAcc.updateBalance(t.getTradeValue());
            sellerAcc.updatePosition(t.getSecurity().getTicker(), -t.getQuantity());

            this.totalFees += buyerFee + sellerFee;
        }
    }
}
