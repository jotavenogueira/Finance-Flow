package repository

import (
	"database/sql"
	"errors"
	"fmt"

	"github.com/jotavenogueira/Finance-Flow/model"
)

type TransactionRepository struct {
	connection *sql.DB
}

func NewTransactionRepository(connection *sql.DB) TransactionRepository {
	return TransactionRepository{connection: connection}
}

func (tr *TransactionRepository) GetTransactions(userID int) ([]model.Transaction, error) {
	rows, err := tr.connection.Query(`
		SELECT t.id, t.description, t.amount, t.type, t.category_id, c.name,
		       to_char(t.date, 'YYYY-MM-DD')
		FROM transaction AS t
		JOIN categories AS c ON c.id = t.category_id AND c.user_id = t.user_id
		WHERE t.user_id = $1
		ORDER BY t.date DESC, t.id DESC`, userID)
	if err != nil {
		return nil, fmt.Errorf("list transactions: %w", err)
	}
	defer rows.Close()

	transactions := make([]model.Transaction, 0)
	for rows.Next() {
		transaction, err := scanTransaction(rows)
		if err != nil {
			return nil, fmt.Errorf("read transaction: %w", err)
		}
		transactions = append(transactions, transaction)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate transactions: %w", err)
	}
	return transactions, nil
}

func (tr *TransactionRepository) GetTransactionById(userID, transactionID int) (*model.Transaction, error) {
	row := tr.connection.QueryRow(`
		SELECT t.id, t.description, t.amount, t.type, t.category_id, c.name,
		       to_char(t.date, 'YYYY-MM-DD')
		FROM transaction AS t
		JOIN categories AS c ON c.id = t.category_id AND c.user_id = t.user_id
		WHERE t.user_id = $1 AND t.id = $2`, userID, transactionID)
	transaction, err := scanTransaction(row)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, model.ErrNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("get transaction: %w", err)
	}
	return &transaction, nil
}

func (tr *TransactionRepository) CreateTransaction(userID int, transaction model.Transaction) (model.Transaction, error) {
	// Select and insert together so an unchecked category cannot cross users.
	row := tr.connection.QueryRow(`
		INSERT INTO transaction (description, amount, type, category, date, user_id, category_id)
		SELECT $2, $3, $4, c.name, $5, $1, c.id
		FROM categories AS c
		WHERE c.user_id = $1 AND c.id = $6
		RETURNING id, description, amount, type, category_id, category,
		          to_char(date, 'YYYY-MM-DD')`,
		userID, transaction.Description, transaction.Amount, transaction.Type,
		transaction.Date, transaction.CategoryID)
	inserted, err := scanTransaction(row)
	if errors.Is(err, sql.ErrNoRows) {
		return model.Transaction{}, model.ErrInvalidCategory
	}
	if err != nil {
		return model.Transaction{}, fmt.Errorf("create transaction: %w", err)
	}
	return inserted, nil
}

func (tr *TransactionRepository) DeleteTransaction(userID, transactionID int) error {
	result, err := tr.connection.Exec(
		"DELETE FROM transaction WHERE user_id = $1 AND id = $2", userID, transactionID,
	)
	if err != nil {
		return fmt.Errorf("delete transaction: %w", err)
	}
	count, err := result.RowsAffected()
	if err != nil {
		return fmt.Errorf("check deleted transaction: %w", err)
	}
	if count == 0 {
		return model.ErrNotFound
	}
	return nil
}

type transactionScanner interface {
	Scan(dest ...any) error
}

func scanTransaction(row transactionScanner) (model.Transaction, error) {
	var transaction model.Transaction
	err := row.Scan(&transaction.ID, &transaction.Description, &transaction.Amount,
		&transaction.Type, &transaction.CategoryID, &transaction.Category, &transaction.Date)
	return transaction, err
}
