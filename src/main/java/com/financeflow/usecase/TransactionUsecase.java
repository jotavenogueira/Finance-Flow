package com.financeflow.usecase;

import com.financeflow.model.DomainError;
import com.financeflow.model.DomainException;
import com.financeflow.model.Transaction;
import com.financeflow.model.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class TransactionUsecase {

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999.99");
    private static final Pattern DATE_SHAPE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd")
            .withResolverStyle(ResolverStyle.STRICT);

    private final TransactionStore repository;

    public TransactionUsecase(TransactionStore repository) {
        this.repository = repository;
    }

    public List<Transaction> getTransactions(int userId) {
        return repository.getTransactions(userId);
    }

    public Transaction createTransaction(int userId, Transaction input) {
        // O nome da categoria vem da categoria do próprio usuário selecionada no banco.
        Transaction transaction = new Transaction(0, Text.trim(input.description()), input.amount(),
                input.type(), input.categoryId(), "", input.date());
        validate(transaction);
        return repository.createTransaction(userId, transaction);
    }

    public Transaction getTransactionById(int userId, int transactionId) {
        return repository.getTransactionById(userId, transactionId);
    }

    public void deleteTransaction(int userId, int transactionId) {
        repository.deleteTransaction(userId, transactionId);
    }

    public static LocalDate parseDate(String value) {
        if (value == null || !DATE_SHAPE.matcher(value).matches()) {
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(value, DATE);
            return date.getYear() < 1 ? null : date;
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static void validate(Transaction transaction) {
        int length = Text.characters(transaction.description());
        if (length < 1 || length > 200) {
            throw new DomainException(DomainError.INVALID_INPUT, "description must contain 1 to 200 characters");
        }
        BigDecimal amount = transaction.amount();
        if (amount == null || amount.signum() <= 0 || amount.compareTo(MAX_AMOUNT) > 0) {
            throw new DomainException(DomainError.INVALID_INPUT, "amount must be positive and at most 99999999.99");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new DomainException(DomainError.INVALID_INPUT, "amount must have at most two decimal places");
        }
        if (!TransactionType.INCOME.equals(transaction.type()) && !TransactionType.EXPENSE.equals(transaction.type())) {
            throw new DomainException(DomainError.INVALID_INPUT, "type must be income or expense");
        }
        if (transaction.categoryId() <= 0) {
            throw new DomainException(DomainError.INVALID_INPUT, "category_id must be a positive integer");
        }
        if (parseDate(transaction.date()) == null) {
            throw new DomainException(DomainError.INVALID_INPUT, "date must be a valid date in YYYY-MM-DD format");
        }
    }
}
