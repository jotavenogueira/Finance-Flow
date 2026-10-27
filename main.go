package main

import (
	"os"

	"github.com/gin-gonic/gin"
	"github.com/joho/godotenv"
	"github.com/jotavenogueira/Finance-Flow/controller"
	"github.com/jotavenogueira/Finance-Flow/db"
	"github.com/jotavenogueira/Finance-Flow/repository"
	"github.com/jotavenogueira/Finance-Flow/usecase"
)

func main() {
	// Variaveis ja definidas no ambiente tem prioridade sobre o .env.
	if err := godotenv.Load(); err != nil && !os.IsNotExist(err) {
		panic(err)
	}
	router := gin.Default()

	dbConnection, err := db.ConnectDB()
	if err != nil {
		panic(err)
	}
	transactionRepository := repository.NewTransactionRepository(dbConnection)
	transactionUsecase := usecase.NewTrasactionUseCase(transactionRepository)
	transactionController := controller.NewTransactionController(transactionUsecase)

	router.GET("/ping", func(c *gin.Context) {
		c.JSON(200, gin.H{
			"message": "pong",
		})
	})
	router.POST("/transactions", transactionController.CreateTransaction)
	router.GET("/transactions", transactionController.GetTransactions)
	router.GET("/transactions/:transactionId", transactionController.GetTransactionsById)
	router.DELETE("/transactions/:transactionId", transactionController.DeleteTransaction)
	router.Run() // listens on 0.0.0.0:8080 by default
}
