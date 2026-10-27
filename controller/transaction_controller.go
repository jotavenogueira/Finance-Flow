package controller

import (
	"net/http"

	"github.com/gin-gonic/gin"
	"github.com/jotavenogueira/Finance-Flow/model"
	"github.com/jotavenogueira/Finance-Flow/usecase"
)

type transactionController struct {
	transactionUsecase usecase.TransactionUsecase
}

func NewTransactionController(usecase usecase.TransactionUsecase) transactionController {
	return transactionController{
		transactionUsecase: usecase,
	}
}

func (t *transactionController) GetTransactions(c *gin.Context) {
	transactions, err := t.transactionUsecase.GetTransactions()

	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
	}

	c.JSON(http.StatusOK, transactions)
}

func (t *transactionController) CreateTransaction(c *gin.Context) {

	var transaction model.Transaction
	err := c.BindJSON(&transaction)

	if err != nil {
		c.JSON(http.StatusBadRequest, err)
		return
	}

	insertedTransaction, err := t.transactionUsecase.CreateTransaction(transaction)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}

	c.JSON(http.StatusOK, insertedTransaction)
}
