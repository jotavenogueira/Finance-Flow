package controller

import (
	"net/http"
	"strconv"

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

func (t *transactionController) GetTransactionsById(c *gin.Context) {

	id := c.Param("transactionId")
	if id == "" {
		response := model.Response{
			Message: "Transaction ID is required",
		}
		c.JSON(http.StatusBadRequest, response)
		return
	}
	productId, err := strconv.Atoi(id)
	if err != nil {
		response := model.Response{
			Message: "ID must be a number",
		}
		c.JSON(http.StatusBadRequest, response)
		return
	}

	transactions, err := t.transactionUsecase.GetTransactionById(productId)

	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
	}

	if transactions == nil {
		response := model.Response{
			Message: "Transaction not found",
		}
		c.JSON(http.StatusNotFound, response)
		return
	}

	c.JSON(http.StatusOK, transactions)
}
