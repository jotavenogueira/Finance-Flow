package com.financeflow.repository;

import com.financeflow.model.DomainError;
import com.financeflow.model.DomainException;
import com.financeflow.model.Transaction;
import com.financeflow.usecase.TransactionStore;
import com.financeflow.usecase.TransactionUsecase;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TransactionRepository implements TransactionStore {

    private static final RowMapper<Transaction> TRANSACTION = (row, index) -> new Transaction(
            row.getInt("id"), row.getString("description"), row.getBigDecimal("amount"), row.getString("type"),
            row.getInt("category_id"), row.getString("category"), row.getString("date"));

    private final JdbcClient jdbc;

    public TransactionRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    @Override
    public List<Transaction> getTransactions(int userId) {
        return jdbc.sql("""
                SELECT t.id, t.description, t.amount, t.type, t.category_id, c.name AS category,
                       to_char(t.date, 'YYYY-MM-DD') AS date
                FROM transaction AS t
                JOIN categories AS c ON c.id = t.category_id AND c.user_id = t.user_id
                WHERE t.user_id = ?
                ORDER BY t.date DESC, t.id DESC""")
                .param(userId)
                .query(TRANSACTION)
                .list();
    }

    @Override
    public Transaction getTransactionById(int userId, int transactionId) {
        return jdbc.sql("""
                SELECT t.id, t.description, t.amount, t.type, t.category_id, c.name AS category,
                       to_char(t.date, 'YYYY-MM-DD') AS date
                FROM transaction AS t
                JOIN categories AS c ON c.id = t.category_id AND c.user_id = t.user_id
                WHERE t.user_id = ? AND t.id = ?""")
                .params(userId, transactionId)
                .query(TRANSACTION)
                .optional()
                .orElseThrow(() -> new DomainException(DomainError.NOT_FOUND));
    }

    @Override
    public Transaction createTransaction(int userId, Transaction transaction) {
        // Seleciona e insere juntos, para que uma categoria não verificada não cruze usuários.
        return jdbc.sql("""
                INSERT INTO transaction (description, amount, type, category, date, user_id, category_id)
                SELECT ?, ?, ?, c.name, ?, c.user_id, c.id
                FROM categories AS c
                WHERE c.user_id = ? AND c.id = ?
                RETURNING id, description, amount, type, category_id, category,
                          to_char(date, 'YYYY-MM-DD') AS date""")
                .params(transaction.description(), transaction.amount(), transaction.type(),
                        TransactionUsecase.parseDate(transaction.date()), userId, transaction.categoryId())
                .query(TRANSACTION)
                .optional()
                .orElseThrow(() -> new DomainException(DomainError.INVALID_CATEGORY));
    }

    @Override
    public void deleteTransaction(int userId, int transactionId) {
        int count = jdbc.sql("DELETE FROM transaction WHERE user_id = ? AND id = ?")
                .params(userId, transactionId)
                .update();
        if (count == 0) {
            throw new DomainException(DomainError.NOT_FOUND);
        }
    }
}
