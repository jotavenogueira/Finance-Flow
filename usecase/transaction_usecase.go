package usecase

import (
	"github.com/jotavenogueira/Finance-Flow/model"
	"github.com/jotavenogueira/Finance-Flow/repository"
)

type TransactionUsecase struct {
	repository repository.TransactionRepository
}

func NewTrasactionUseCase(
	transactionRepository repository.TransactionRepository,
) TransactionUsecase {
	return TransactionUsecase{
		repository: transactionRepository,
	}
}

func (tu *TransactionUsecase) GetTransactions() ([]model.Transaction, error) {
	return tu.repository.GetTransactions()
}

func (tu *TransactionUsecase) CreateTransaction(transaction model.Transaction) (model.Transaction, error) {

	transactionId, err := tu.repository.CreateTransaction(transaction)
	if err != nil {
		return model.Transaction{}, err
	}

	transaction.ID = transactionId
	return transaction, nil
}

func (tu *TransactionUsecase) GetTransactionById(id_transaction int) (*model.Transaction, error) {
	transaction, err := tu.repository.GetTransactionById(id_transaction)
	if err != nil {
		return nil, err
	}
	return transaction, nil
}

func (tu *TransactionUsecase) DeleteTransaction(id_transaction int) error {
	err := tu.repository.DeleteTransaction(id_transaction)
	if err != nil {
		return err
	}
	return nil
}
