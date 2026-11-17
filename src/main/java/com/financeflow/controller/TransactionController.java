package com.financeflow.controller;

import com.financeflow.model.DomainError;
import com.financeflow.model.DomainException;
import com.financeflow.model.Transaction;
import com.financeflow.model.User;
import com.financeflow.usecase.TransactionUsecase;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TransactionController {

    private final TransactionUsecase transactions;

    public TransactionController(TransactionUsecase transactions) {
        this.transactions = transactions;
    }

    /** O proprietário vem da sessão; um user_id enviado no JSON é ignorado. */
    record CreateTransactionRequest(String description, BigDecimal amount, String type, int categoryId,
                                    String date) {
    }

    @GetMapping("/transactions")
    public List<Transaction> getTransactions(@RequestAttribute(AuthInterceptor.AUTHENTICATED_USER) User user) {
        return transactions.getTransactions(user.id());
    }

    @PostMapping("/transactions")
    public ResponseEntity<Transaction> createTransaction(
            @RequestAttribute(AuthInterceptor.AUTHENTICATED_USER) User user,
            @RequestBody CreateTransactionRequest request) {
        Transaction created = transactions.createTransaction(user.id(), new Transaction(0, request.description(),
                request.amount(), request.type(), request.categoryId(), "", request.date()));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/transactions/{transactionId}")
    public Transaction getTransactionById(@RequestAttribute(AuthInterceptor.AUTHENTICATED_USER) User user,
                                          @PathVariable String transactionId) {
        return transactions.getTransactionById(user.id(), parseTransactionId(transactionId));
    }

    @DeleteMapping("/transactions/{transactionId}")
    public ResponseEntity<Void> deleteTransaction(@RequestAttribute(AuthInterceptor.AUTHENTICATED_USER) User user,
                                                  @PathVariable String transactionId) {
        transactions.deleteTransaction(user.id(), parseTransactionId(transactionId));
        return ResponseEntity.noContent().build();
    }

    private static int parseTransactionId(String value) {
        try {
            int id = Integer.parseInt(value);
            if (id > 0) {
                return id;
            }
        } catch (NumberFormatException ignored) {
            // Tratado abaixo com a mesma resposta de ID inválido.
        }
        throw new DomainException(DomainError.INVALID_INPUT, "transaction ID must be a positive integer");
    }
}
