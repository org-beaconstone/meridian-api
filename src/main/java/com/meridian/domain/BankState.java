package com.meridian.domain;

import java.util.ArrayList;
import java.util.List;

public class BankState {
    private int version = 1;
    private long balance;
    private List<Transaction> transactions;
    private List<Budget> budgets;

    public BankState() {
        this.transactions = new ArrayList<>();
        this.budgets = new ArrayList<>();
    }

    public BankState(long balance) {
        this();
        this.balance = balance;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public long getBalance() {
        return balance;
    }

    public void setBalance(long balance) {
        this.balance = balance;
    }

    public List<Transaction> getTransactions() {
        return transactions;
    }

    public void setTransactions(List<Transaction> transactions) {
        this.transactions = transactions;
    }

    public List<Budget> getBudgets() {
        return budgets;
    }

    public void setBudgets(List<Budget> budgets) {
        this.budgets = budgets;
    }
}
