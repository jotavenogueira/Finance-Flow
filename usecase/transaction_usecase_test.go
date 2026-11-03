package usecase

import (
	"errors"
	"math"
	"strings"
	"testing"

	"github.com/jotavenogueira/Finance-Flow/model"
)

type transactionStoreStub struct {
	created bool
	userID  int
	input   model.Transaction
	err     error
}

func (s *transactionStoreStub) GetTransactions(userID int) ([]model.Transaction, error) {
	s.userID = userID
	return []model.Transaction{}, s.err
}

func (s *transactionStoreStub) GetTransactionById(userID, transactionID int) (*model.Transaction, error) {
	s.userID = userID
	return &model.Transaction{ID: transactionID}, s.err
}

func (s *transactionStoreStub) CreateTransaction(userID int, transaction model.Transaction) (model.Transaction, error) {
	s.created, s.userID, s.input = true, userID, transaction
	transaction.ID = 7
	return transaction, s.err
}

func (s *transactionStoreStub) DeleteTransaction(userID, transactionID int) error {
	s.userID = userID
	return s.err
}

func validTransaction() model.Transaction {
	return model.Transaction{
		Description: "Lunch", Amount: 12.34, Type: model.TypeExpense,
		CategoryID: 3, Date: "2024-02-29",
	}
}

func TestCreateTransactionRejectsInvalidInputBeforeStorage(t *testing.T) {
	tests := []struct {
		name   string
		mutate func(*model.Transaction)
	}{
		{"blank description", func(v *model.Transaction) { v.Description = " \t\n" }},
		{"long unicode description", func(v *model.Transaction) { v.Description = strings.Repeat("界", 201) }},
		{"zero amount", func(v *model.Transaction) { v.Amount = 0 }},
		{"negative amount", func(v *model.Transaction) { v.Amount = -1 }},
		{"amount overflow", func(v *model.Transaction) { v.Amount = 100000000 }},
		{"fractional cent", func(v *model.Transaction) { v.Amount = 1.001 }},
		{"NaN amount", func(v *model.Transaction) { v.Amount = math.NaN() }},
		{"infinite amount", func(v *model.Transaction) { v.Amount = math.Inf(1) }},
		{"unknown type", func(v *model.Transaction) { v.Type = "transfer" }},
		{"missing category", func(v *model.Transaction) { v.CategoryID = 0 }},
		{"negative category", func(v *model.Transaction) { v.CategoryID = -1 }},
		{"impossible date", func(v *model.Transaction) { v.Date = "2025-02-29" }},
		{"noncanonical date", func(v *model.Transaction) { v.Date = "2024-2-29" }},
		{"timestamp", func(v *model.Transaction) { v.Date = "2024-02-29T00:00:00Z" }},
		{"year zero", func(v *model.Transaction) { v.Date = "0000-01-01" }},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			transaction := validTransaction()
			tt.mutate(&transaction)
			store := &transactionStoreStub{}
			service := NewTransactionUsecase(store)
			_, err := service.CreateTransaction(42, transaction)
			if !errors.Is(err, model.ErrInvalidInput) {
				t.Fatalf("expected invalid input, got %v", err)
			}
			if store.created {
				t.Fatal("invalid transaction reached storage")
			}
		})
	}
}

func TestCreateTransactionAcceptsMoneyAndUnicodeBoundaries(t *testing.T) {
	for _, amount := range []float64{0.01, 0.1, 10, 12.34, 99999999.99} {
		store := &transactionStoreStub{}
		service := NewTransactionUsecase(store)
		input := validTransaction()
		input.Description = "  " + strings.Repeat("界", 200) + "  "
		input.Category = "untrusted category name"
		input.Amount = amount
		result, err := service.CreateTransaction(42, input)
		if err != nil {
			t.Fatalf("valid amount %v rejected: %v", amount, err)
		}
		if !store.created || store.userID != 42 || store.input.Description != strings.Repeat("界", 200) {
			t.Fatalf("normalized input or owner not preserved: %+v", store)
		}
		if store.input.Category != "" || result.ID != 7 {
			t.Fatalf("category name must come from storage: %+v", result)
		}
	}
}

func TestCreateTransactionPreservesCategoryOwnershipError(t *testing.T) {
	store := &transactionStoreStub{err: model.ErrInvalidCategory}
	service := NewTransactionUsecase(store)
	_, err := service.CreateTransaction(42, validTransaction())
	if !errors.Is(err, model.ErrInvalidCategory) {
		t.Fatalf("expected category ownership error, got %v", err)
	}
}
