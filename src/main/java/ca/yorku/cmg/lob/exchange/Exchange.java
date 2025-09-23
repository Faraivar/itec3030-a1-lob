/**
 * Copyright (C) Sotirios Liaskos (liaskos@yorku.ca) - All Rights Reserved
 * 
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holder.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holder.  If you encounter this file and do not have
 * permission, please contact the copyright holder and delete this file.
 */
package ca.yorku.cmg.lob.exchange;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;

import ca.yorku.cmg.lob.orderbook.Ask;
import ca.yorku.cmg.lob.orderbook.Bid;
import ca.yorku.cmg.lob.orderbook.OrderOutcome;
import ca.yorku.cmg.lob.orderbook.Orderbook;
import ca.yorku.cmg.lob.orderbook.Trade;
import ca.yorku.cmg.lob.security.Security;
import ca.yorku.cmg.lob.security.SecurityList;
import ca.yorku.cmg.lob.trader.Trader;
import ca.yorku.cmg.lob.trader.TraderInstitutional;
import ca.yorku.cmg.lob.trader.TraderRetail;
import ca.yorku.cmg.lob.tradestandards.IOrder;
import ca.yorku.cmg.lob.tradestandards.ITrade;

/**
 * Represents a stock exchange that manages securities, accounts, orders, and trades.
 */
public class Exchange {

	Orderbook book;
	SecurityList securities = new SecurityList();
	AccountsList accounts = new AccountsList();
	ArrayList<Trade> tradesLog = new ArrayList<Trade>();
	
	long totalFees = 0;
	
    /**
     * Default constructor for the Exchange class.
     */
	public Exchange(){
		book = new Orderbook();
	}
	
	
    /**
     * Validates an order to ensure it complies with exchange rules. Checks if trader and security are supported by the exchange, and that the trader has enough balance of the exchange. 
     * 
     * @param o the {@linkplain ca.yorku.cmg.lob.tradestandards.IOrder}-implementing object to be validated
     * @return {@code true} if the order is valid, {@code false} otherwise
     */
	public boolean validateOrder(IOrder o) {
		// Does ticker exist? See if the security associated with the order exists in the list of securities
		if (securities.getSecurityByTicker(o.getSecurity().getTicker()) == null) {
			System.err.println("Order validation: ticker " + o.getSecurity().getTicker() + " not supported.");
			return (false);
		}
		
		//Does the trader exist? Check to see if the trader exists 
		if (accounts.getTraderByID(o.getTrader().getID()) == null) {
			System.err.println("Order validation: trader with ID " + o.getTrader().getID() + " not registered with the exchange.");
			return (false);
		}

		//Put in pos the position that the trader mentioned in the order has in the security mentioned in the order
		int pos = accounts.getTraderAccount(o.getTrader()).getPosition(o.getSecurity().getTicker());
		//Get the balance the trader has with the exchange
		long bal = accounts.getTraderAccount(o.getTrader()).getBalance();

		// Does ask trader have position at the security sufficient for a sell?
		if ((o instanceof Ask) && (pos < o.getQuantity())) {
			System.err.println("Order validation: seller with ID " + o.getTrader().getID() + " not enough shares of " + o.getSecurity().getTicker() + ": has " + pos + " and tries to sell " + o.getQuantity());
			return (false);
		}
		
		// Does bid trader have balance sufficient for a buy?
		if ((o instanceof Bid) && (bal < o.getValue())) {
			System.err.println(
					String.format("Order validation: buyer with ID %d does not have enough balance: has $%,.2f and tries to buy for $%,.2f",
							o.getTrader().getID(), bal/100.0,o.getValue()/100.0));
					
			return (false);
		}

		return (true);
	}
	
    /**
     * Submits an order to the exchange for processing.
     * 
     * @param o     the {@linkplain ca.yorku.cmg.lob.tradestandards.IOrder}-implementing object to be processed
     * @param time the timestamp of the order submission (seconds)
     */
	public void submitOrder(IOrder o, long time) {
		if (!validateOrder(o)){
			return;
		}
		
		OrderOutcome oOutcome;
		
		//This is a bid for a security
		if (o instanceof Bid) {// Order is a bid
			//Go to the asks half-book, see if there are matching asks (selling offers) and process them
			oOutcome = book.getAsks().processOrder(o, time);
			//If the quanity of the unfulfilled order in the outcome is not zero
			if (oOutcome.getUnfulfilledOrder().getQuantity() > 0) {
				//Not the entire order was fulfilled, add the unfulfilled order to the bid half-book 
				book.getBids().addOrder((Bid) oOutcome.getUnfulfilledOrder());
				
			}
		} else { //order is an ask
			//Go to the bids half-book and see if there are matching bids (buying offers) and process them
			oOutcome = book.getBids().processOrder(o, time);
			//If the quanity of the unfulfilled order in the outcome is not zero
			if (oOutcome.getUnfulfilledOrder().getQuantity() > 0) {
				// Not the entire order was fulfilled, add it to the bid half-book
				book.getAsks().addOrder((Ask) oOutcome.getUnfulfilledOrder());
				
			}			
		}

		//Save resulting trades to the tradesLog
		if (oOutcome.getResultingTrades() != null) {
			tradesLog.addAll(oOutcome.getResultingTrades());
		} else {
			return;
		}
		
		//Calculate Fees for the trades
		for (ITrade t:oOutcome.getResultingTrades()) {
			
			//Update balances for Buyer
			
			//Get the fee that they buyer is supposed to pay
			t.setBuyerFee(accounts.getTraderAccount(t.getBuyer()).getFee(t));
			//Apply the above fee to the account balance of the buyer 	
			accounts.getTraderAccount(t.getBuyer()).applyFee(t);
			//Apply the trade payment to the account balance of the buyer (they spent money)
			accounts.getTraderAccount(t.getBuyer()).withdrawMoney(t.getValue());
			//Add the bought stocks to the position of the buyer
			accounts.getTraderAccount(t.getBuyer()).addToPosition(t.getSecurity().getTicker(), t.getQuantity());
			
			//Update balances for Seller
			
			//Get the fee tWhat the seller is supposed to pay
			t.setSellerFee(accounts.getTraderAccount(t.getSeller()).getFee(t));
			//Apply the above fee to the account balance of the seller
			accounts.getTraderAccount(t.getSeller()).applyFee(t);
			//Apply the trade payment to the account balance of the seller (they earned money)
			accounts.getTraderAccount(t.getSeller()).addMoney(t.getValue());
			//Deduct the sold stocks from the position of the seller
			accounts.getTraderAccount(t.getSeller()).deductFromPosition(t.getSecurity().getTicker(), t.getQuantity());
			
			this.totalFees += t.getBuyerFee() + t.getSellerFee(); 
		}
	}
	
	
	
	//
	// I O 
	//
	
	
	
	
	
	
    /**
     * Reads the security list from a file and populates the exchange.
     * 
     * @param path the path to the security list file
     */
	public void readSecurityListfromFile(String path) {
	    try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            boolean isFirstLine = true; // Skip header

            while ((line = br.readLine()) != null) {
                if (isFirstLine) {
                    isFirstLine = false;
                    continue;
                }
                String[] parts = line.split(",", -1); // Split by comma
                if (parts.length >= 2) {
                    String code = parts[0].trim();
                    String description = parts[1].trim();
                    securities.addSecurity(code, description);
                } else {
                    System.err.println("Skipping malformed line: " + line);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
	}
	
    /**
     * Reads the accounts list from a file and populates the exchange.
     * 
     * @param path the path to the accounts list file
     */
	public void readAccountsListFromFile(String path) {
	    try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            boolean isFirstLine = true; // Skip header

            while ((line = br.readLine()) != null) {
                if (isFirstLine) {
                    isFirstLine = false;
                    continue;
                }
                String[] parts = line.split(",", -1); // Split by comma
                if (parts.length >= 4) {
                    String traderTitle = parts[0].trim();
                    String traderType = parts[1].trim();
                    String accType = parts[2].trim();
                    long initBalance = Long.parseLong(parts[3].trim());
                	Trader t;
                    if (traderType.equals("Retail")) {
                    	t = new TraderRetail(traderTitle);
                    } else {
                    	t = new TraderInstitutional(traderTitle);
                    }
                    if (accType.equals("Basic")) {
                    	accounts.addAccount(new AccountBasic(t,initBalance));
                    } else {
                    	accounts.addAccount(new AccountPro(t,initBalance));
                    }
                } else {
                    System.err.println("Skipping malformed line (two few attributes): " + line);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
	}
	
    /**
     * Reads initial positions from a file and updates account holdings.
     * 
     * @param path the path to the initial positions file
     */
	public void readInitialPositionsFromFile(String path) {
	    try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            boolean isFirstLine = true; // Skip header

            while ((line = br.readLine()) != null) {
                if (isFirstLine) {
                    isFirstLine = false;
                    continue;
                }
                String[] parts = line.split(",", -1); // Split by comma
                if (parts.length >= 3) {
                    Integer tid = Integer.valueOf(parts[0].trim());
                    String tkr = parts[1].trim();
                    Integer count = Integer.valueOf(parts[2].trim());
                    Trader trad = accounts.getTraderByID(tid); 
                    //does the trader id have an account? Is the ticker supported?
                    if (trad == null) {
                    	System.err.println("Initial Balances: Trader does not exist: " + line);
                    } else if (securities.getSecurityByTicker(tkr) == null) { 
                    	System.err.println("Initial Balances: Ticker not traded in this exchange: " + line);
                    } else {
                    	accounts.getTraderAccount(trad).updatePosition(tkr, count);
                    }
                } else {
                    System.err.println("Skipping malformed line (too few attributes): " + line);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
	}
		
    /**
     * Processes a file containing orders and submits them to the exchange.
     * 
     * @param path the path to the orders file
     */
	public void processOrderFile(String path) {
	    try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            boolean isFirstLine = true; // Skip header

            while ((line = br.readLine()) != null) {
                if (isFirstLine) {
                    isFirstLine = false;
                    continue;
                }
                String[] parts = line.split(",", -1); // Split by comma
                if (parts.length >= 6) {
                    int traderID = Integer.valueOf(parts[0].trim());
                    String tkr = parts[1].trim();
                    String type = parts[2].trim();
                    int qty = Integer.valueOf(parts[3].trim());
                    int price = Integer.valueOf(parts[4].trim());
                    long time = Long.valueOf(parts[5].trim());
                    
                    Trader t = getAccounts().getTraderByID(traderID);
                    Security sec = getSecurities().getSecurityByTicker(tkr); 
                    
                    if ((t!=null) && (sec!=null)) {
                        if (type.equals("ask")) {
                        	submitOrder(new Ask(t,sec,price,qty,time), time);
                        } else if (type.equals("bid")) {
                        	submitOrder(new Bid(t,sec,price,qty,time), time);
                        } else {
                        	System.err.println("Order type not found (skipping): " + line);
                        }
                    }
                } else {
                    System.err.println("Skipping malformed line: " + line);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
	}
	
    /**
     * Prints a table of current ask orders.
     * 
     * @param header whether to include a header in the output
     * @return a string representation of the ask table
     */
	public String printAskTable(boolean header) {
		return(book.getAsks().printAllOrders(header));
	}
	
    /**
     * Prints a table of current bid orders.
     * 
     * @param header whether to include a header in the output
     * @return a string representation of the bid table
     */
	public String printBidTable(boolean header) {
		return(book.getBids().printAllOrders(header));
	}
	
    /**
     * Prints a log of completed trades.
     * 
     * @param header whether to include a header in the output
     * @return a string representation of the trades log
     */
	public String printTradesLog(boolean header) {
		String output = "";
		if (header) {
			output = "[From____  To______  Tkr_  Quantity  Price__  Time____]\n";
			//"[%8d  %8d  %s  %8d  %7.2f  %8d]\n", 
		}
		for (Trade t: tradesLog) {
			output += t.toString();
		}
		return (output);
	}

    /**
     * Prints account balances of the exchange's customers
     * 
     * @param header whether to include a header in the output
     * @return a string representation of account balances
     */
	public String printBalances(boolean header) {
		return(accounts.debugPrintBalances(header));
	}
	
    /**
     * Prints the total fees collected by the exchange.
     * 
     * @param header whether to include a header in the output
     * @return a string representation of fees collected
     */
	public String printFeesCollected(boolean header) {
		if (header) {
			return (String.format("            Fees Collected TOTAL: %16s", 
					String.format("$%,.2f",this.totalFees/100.0)));
		} else {
			return (String.format("%16s", 
					String.format("$%,.2f",this.totalFees/100.0)));
		}
	}
	
	
	
	
	//
	// G E T T E R S
	//
		
	
    /**
     * Retrieves the list of securities managed by the exchange.
     * 
     * @return the {@linkplain ca.yorku.cmg.lob.security.SecurityList} object
     */
	public SecurityList getSecurities() {
		return securities;
	}
	
    /**
     * Retrieves the list of accounts managed by the exchange.
     * 
     * @return the {@linkplain ca.yorku.cmg.lob.exchange.AccountsList} object
     */
	public AccountsList getAccounts() {
		return accounts;
	}
}
