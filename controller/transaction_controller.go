package controller

import (
	"fmt"
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"
	"github.com/jotavenogueira/Finance-Flow/model"
	"github.com/jotavenogueira/Finance-Flow/usecase"
)

type TransactionController struct {
	transactionUsecase usecase.TransactionUsecase
}

func NewTransactionController(transactionUsecase usecase.TransactionUsecase) TransactionController {
	return TransactionController{transactionUsecase: transactionUsecase}
}

type createTransactionRequest struct {
	Description string                `json:"description"`
	Amount      float64               `json:"amount"`
	Type        model.TransactionType `json:"type"`
	CategoryID  int                   `json:"category_id"`
	Date        string                `json:"date"`
}

func (t *TransactionController) GetTransactions(c *gin.Context) {
	transactions, err := t.transactionUsecase.GetTransactions(CurrentUserID(c))
	if err != nil {
		respondError(c, err)
		return
	}
	c.JSON(http.StatusOK, transactions)
}

func (t *TransactionController) CreateTransaction(c *gin.Context) {
	var request createTransactionRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		respondError(c, model.ErrInvalidInput)
		return
	}
	transaction, err := t.transactionUsecase.CreateTransaction(CurrentUserID(c), model.Transaction{
		Description: request.Description,
		Amount:      request.Amount,
		Type:        request.Type,
		CategoryID:  request.CategoryID,
		Date:        request.Date,
	})
	if err != nil {
		respondError(c, err)
		return
	}
	c.JSON(http.StatusCreated, transaction)
}

func (t *TransactionController) GetTransactionsById(c *gin.Context) {
	transactionID, err := parseTransactionID(c)
	if err != nil {
		respondError(c, err)
		return
	}
	transaction, err := t.transactionUsecase.GetTransactionById(CurrentUserID(c), transactionID)
	if err != nil {
		respondError(c, err)
		return
	}
	c.JSON(http.StatusOK, transaction)
}

func (t *TransactionController) DeleteTransaction(c *gin.Context) {
	transactionID, err := parseTransactionID(c)
	if err != nil {
		respondError(c, err)
		return
	}
	if err := t.transactionUsecase.DeleteTransaction(CurrentUserID(c), transactionID); err != nil {
		respondError(c, err)
		return
	}
	c.Status(http.StatusNoContent)
}

func parseTransactionID(c *gin.Context) (int, error) {
	id, err := strconv.Atoi(c.Param("transactionId"))
	if err != nil || id <= 0 {
		return 0, fmt.Errorf("%w: transaction ID must be a positive integer", model.ErrInvalidInput)
	}
	return id, nil
}
