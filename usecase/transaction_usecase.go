package usecase

import (
	"fmt"
	"math"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/jotavenogueira/Finance-Flow/model"
)

type TransactionStore interface {
	GetTransactions(userID int) ([]model.Transaction, error)
	GetTransactionById(userID, transactionID int) (*model.Transaction, error)
	CreateTransaction(userID int, transaction model.Transaction) (model.Transaction, error)
	DeleteTransaction(userID, transactionID int) error
}

type TransactionUsecase struct {
	repository TransactionStore
}

func NewTransactionUsecase(transactionRepository TransactionStore) TransactionUsecase {
	return TransactionUsecase{repository: transactionRepository}
}

func (tu *TransactionUsecase) GetTransactions(userID int) ([]model.Transaction, error) {
	return tu.repository.GetTransactions(userID)
}

func (tu *TransactionUsecase) CreateTransaction(userID int, transaction model.Transaction) (model.Transaction, error) {
	transaction.Description = strings.TrimSpace(transaction.Description)
	if err := validateTransaction(transaction); err != nil {
		return model.Transaction{}, err
	}
	// The category name comes from the owned category selected in the database.
	transaction.Category = ""
	return tu.repository.CreateTransaction(userID, transaction)
}

func (tu *TransactionUsecase) GetTransactionById(userID, transactionID int) (*model.Transaction, error) {
	return tu.repository.GetTransactionById(userID, transactionID)
}

func (tu *TransactionUsecase) DeleteTransaction(userID, transactionID int) error {
	return tu.repository.DeleteTransaction(userID, transactionID)
}

func validateTransaction(transaction model.Transaction) error {
	if length := utf8.RuneCountInString(transaction.Description); length < 1 || length > 200 {
		return fmt.Errorf("%w: description must contain 1 to 200 characters", model.ErrInvalidInput)
	}
	if math.IsNaN(transaction.Amount) || math.IsInf(transaction.Amount, 0) ||
		transaction.Amount <= 0 || transaction.Amount > 99999999.99 {
		return fmt.Errorf("%w: amount must be positive and at most 99999999.99", model.ErrInvalidInput)
	}
	decimal := strconv.FormatFloat(transaction.Amount, 'f', -1, 64)
	if point := strings.IndexByte(decimal, '.'); point >= 0 && len(decimal)-point-1 > 2 {
		return fmt.Errorf("%w: amount must have at most two decimal places", model.ErrInvalidInput)
	}
	if transaction.Type != model.TypeIncome && transaction.Type != model.TypeExpense {
		return fmt.Errorf("%w: type must be income or expense", model.ErrInvalidInput)
	}
	if transaction.CategoryID <= 0 {
		return fmt.Errorf("%w: category_id must be a positive integer", model.ErrInvalidInput)
	}
	date, err := time.Parse("2006-01-02", transaction.Date)
	if err != nil || date.Year() < 1 || date.Format("2006-01-02") != transaction.Date {
		return fmt.Errorf("%w: date must be a valid date in YYYY-MM-DD format", model.ErrInvalidInput)
	}
	return nil
}
