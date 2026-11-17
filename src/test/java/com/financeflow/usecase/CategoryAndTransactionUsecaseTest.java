package com.financeflow.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.financeflow.model.Category;
import com.financeflow.model.DomainError;
import com.financeflow.model.DomainException;
import com.financeflow.model.Transaction;
import com.financeflow.model.TransactionType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class CategoryAndTransactionUsecaseTest {

    static class CategoryStoreStub implements CategoryStore {
        boolean created;
        int userId;
        String name;
        RuntimeException failure;

        @Override
        public List<Category> getCategories(int userId) {
            this.userId = userId;
            return List.of();
        }

        @Override
        public Category createCategory(int userId, String name) {
            created = true;
            this.userId = userId;
            this.name = name;
            if (failure != null) {
                throw failure;
            }
            return new Category(7, name);
        }
    }

    static class TransactionStoreStub implements TransactionStore {
        boolean created;
        int userId;
        Transaction input;
        RuntimeException failure;

        @Override
        public List<Transaction> getTransactions(int userId) {
            this.userId = userId;
            return List.of();
        }

        @Override
        public Transaction getTransactionById(int userId, int transactionId) {
            this.userId = userId;
            return null;
        }

        @Override
        public Transaction createTransaction(int userId, Transaction transaction) {
            created = true;
            this.userId = userId;
            input = transaction;
            if (failure != null) {
                throw failure;
            }
            return new Transaction(7, transaction.description(), transaction.amount(), transaction.type(),
                    transaction.categoryId(), transaction.category(), transaction.date());
        }

        @Override
        public void deleteTransaction(int userId, int transactionId) {
            this.userId = userId;
        }
    }

    private static void assertError(DomainError expected, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class,
                exception -> assertThat(exception.error()).isEqualTo(expected));
    }

    private static Transaction validTransaction() {
        return new Transaction(0, "Lunch", new BigDecimal("12.34"), TransactionType.EXPENSE, 3, "", "2024-02-29");
    }

    private static Transaction with(Transaction t, String description, BigDecimal amount, String type,
                                    int categoryId, String date) {
        return new Transaction(t.id(), description, amount, type, categoryId, t.category(), date);
    }

    @Test
    void createCategoryRejectsEmptyAndOverlongNames() {
        for (String name : new String[] {"", " \t\n", null, "界".repeat(101)}) {
            CategoryStoreStub store = new CategoryStoreStub();
            assertError(DomainError.INVALID_INPUT, () -> new CategoryUsecase(store).createCategory(42, name));
            assertThat(store.created).isFalse();
        }
    }

    @Test
    void createCategoryTrimsNameAndAcceptsHundredUnicodeCharacters() {
        CategoryStoreStub store = new CategoryStoreStub();
        String name = "界".repeat(100);
        Category category = new CategoryUsecase(store).createCategory(42, "  " + name + "\t");
        assertThat(category.name()).isEqualTo(name);
        assertThat(store.name).isEqualTo(name);
        assertThat(store.userId).isEqualTo(42);
    }

    @Test
    void createCategoryPreservesDuplicateError() {
        CategoryStoreStub store = new CategoryStoreStub();
        store.failure = new DomainException(DomainError.CONFLICT);
        assertError(DomainError.CONFLICT, () -> new CategoryUsecase(store).createCategory(42, "Food"));
    }

    @Test
    void createTransactionRejectsInvalidInputBeforeStorage() {
        Map<String, UnaryOperator<Transaction>> mutations = Map.ofEntries(
                Map.entry("blank description", t -> with(t, " \t\n", t.amount(), t.type(), t.categoryId(), t.date())),
                Map.entry("missing description", t -> with(t, null, t.amount(), t.type(), t.categoryId(), t.date())),
                Map.entry("long unicode description",
                        t -> with(t, "界".repeat(201), t.amount(), t.type(), t.categoryId(), t.date())),
                Map.entry("missing amount", t -> with(t, t.description(), null, t.type(), t.categoryId(), t.date())),
                Map.entry("zero amount",
                        t -> with(t, t.description(), BigDecimal.ZERO, t.type(), t.categoryId(), t.date())),
                Map.entry("negative amount",
                        t -> with(t, t.description(), new BigDecimal("-1"), t.type(), t.categoryId(), t.date())),
                Map.entry("amount overflow", t -> with(t, t.description(), new BigDecimal("100000000"), t.type(),
                        t.categoryId(), t.date())),
                Map.entry("fractional cent", t -> with(t, t.description(), new BigDecimal("1.001"), t.type(),
                        t.categoryId(), t.date())),
                Map.entry("unknown type",
                        t -> with(t, t.description(), t.amount(), "transfer", t.categoryId(), t.date())),
                Map.entry("missing category", t -> with(t, t.description(), t.amount(), t.type(), 0, t.date())),
                Map.entry("negative category", t -> with(t, t.description(), t.amount(), t.type(), -1, t.date())),
                Map.entry("impossible date",
                        t -> with(t, t.description(), t.amount(), t.type(), t.categoryId(), "2025-02-29")),
                Map.entry("noncanonical date",
                        t -> with(t, t.description(), t.amount(), t.type(), t.categoryId(), "2024-2-29")),
                Map.entry("timestamp", t -> with(t, t.description(), t.amount(), t.type(), t.categoryId(),
                        "2024-02-29T00:00:00Z")),
                Map.entry("year zero",
                        t -> with(t, t.description(), t.amount(), t.type(), t.categoryId(), "0000-01-01")));
        mutations.forEach((name, mutation) -> {
            TransactionStoreStub store = new TransactionStoreStub();
            Transaction input = mutation.apply(validTransaction());
            assertThatThrownBy(() -> new TransactionUsecase(store).createTransaction(42, input))
                    .as(name)
                    .isInstanceOfSatisfying(DomainException.class,
                            exception -> assertThat(exception.error()).isEqualTo(DomainError.INVALID_INPUT));
            assertThat(store.created).as(name).isFalse();
        });
    }

    @Test
    void createTransactionAcceptsMoneyAndUnicodeBoundaries() {
        for (String amount : new String[] {"0.01", "0.1", "10", "12.34", "12.340", "99999999.99"}) {
            TransactionStoreStub store = new TransactionStoreStub();
            Transaction valid = validTransaction();
            Transaction input = new Transaction(0, "  " + "界".repeat(200) + "  ", new BigDecimal(amount),
                    valid.type(), valid.categoryId(), "untrusted category name", valid.date());
            Transaction result = new TransactionUsecase(store).createTransaction(42, input);
            assertThat(store.created).isTrue();
            assertThat(store.userId).isEqualTo(42);
            assertThat(store.input.description()).isEqualTo("界".repeat(200));
            assertThat(store.input.category()).isEmpty();
            assertThat(result.id()).isEqualTo(7);
        }
    }

    @Test
    void createTransactionPreservesCategoryOwnershipError() {
        TransactionStoreStub store = new TransactionStoreStub();
        store.failure = new DomainException(DomainError.INVALID_CATEGORY);
        assertError(DomainError.INVALID_CATEGORY,
                () -> new TransactionUsecase(store).createTransaction(42, validTransaction()));
    }
}
