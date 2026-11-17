package com.financeflow.usecase;

import com.financeflow.model.Transaction;
import java.util.List;

public interface TransactionStore {

    List<Transaction> getTransactions(int userId);

    Transaction getTransactionById(int userId, int transactionId);

    Transaction createTransaction(int userId, Transaction transaction);

    void deleteTransaction(int userId, int transactionId);
}
