package repository

import (
	"database/sql"
	"fmt"

	"github.com/jotavenogueira/Finance-Flow/model"
)

type TransactionRepository struct {
	connection *sql.DB
}

func NewTransactionRepository(connection *sql.DB) TransactionRepository {
	return TransactionRepository{connection: connection}
}

func (tr *TransactionRepository) GetTransactions() ([]model.Transaction, error) {
	query := "SELECT id, description, amount, type, category, date FROM transaction"
	rows, err := tr.connection.Query(query)
	if err != nil {
		fmt.Println("Error executing query:", err)
		return []model.Transaction{}, err
	}

	var transactionList []model.Transaction
	var transactionObj model.Transaction

	for rows.Next() {
		err = rows.Scan(
			&transactionObj.ID,
			&transactionObj.Description,
			&transactionObj.Amount,
			&transactionObj.Type,
			&transactionObj.Category,
			&transactionObj.Date,
		)

		if err != nil {
			fmt.Println("Error executing query:", err)
			return []model.Transaction{}, err
		}

		transactionList = append(transactionList, transactionObj)
	}

	rows.Close()

	return transactionList, nil
}

func (tr *TransactionRepository) GetTransactionById(id_transaction int) (*model.Transaction, error) {
	query, err := tr.connection.Prepare("SELECT * FROM transaction WHERE id = $1")
	if err != nil {
		fmt.Println("Error preparing query:", err)
		return nil, err
	}

	var transaction model.Transaction
	err = query.QueryRow(id_transaction).Scan(
		&transaction.ID,
		&transaction.Description,
		&transaction.Amount,
		&transaction.Type,
		&transaction.Category,
		&transaction.Date,
	)

	if err != nil {
		if err == sql.ErrNoRows {
			return nil, fmt.Errorf("transaction not found")
		}

		return nil, fmt.Errorf("error executing query: %v", err)
	}

	query.Close()
	return &transaction, nil
}

func (tr *TransactionRepository) CreateTransaction(transaction model.Transaction) (int, error) {

	var id int
	query, err := tr.connection.Prepare("INSERT INTO transaction" +
		"(description, amount, type, category, date)" +
		"VALUES ($1, $2, $3, $4, $5) RETURNING id")
	if err != nil {
		fmt.Println("Error preparing query:", err)
		return 0, err
	}

	err = query.QueryRow(transaction.Description, transaction.Amount,
		transaction.Type, transaction.Category,
		transaction.Date).Scan(&id)
	if err != nil {
		fmt.Println("Error executing query:", err)
		return 0, err
	}

	query.Close()
	return id, nil
}
