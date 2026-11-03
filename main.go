package main

import (
	"log"
	"net/http"
	"os"
	"time"

	"github.com/joho/godotenv"
	"github.com/jotavenogueira/Finance-Flow/db"
)

func main() {
	// Variaveis ja definidas no ambiente tem prioridade sobre o .env.
	if err := godotenv.Load(); err != nil && !os.IsNotExist(err) {
		panic(err)
	}
	dbConnection, err := db.ConnectDB()
	if err != nil {
		panic(err)
	}
	defer dbConnection.Close()
	if err := db.Migrate(dbConnection); err != nil {
		log.Fatal(err)
	}
	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}
	server := &http.Server{
		Addr:              ":" + port,
		Handler:           NewHandler(dbConnection),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       60 * time.Second,
	}
	log.Printf("FinanceFlow disponível na porta %s", port)
	if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		log.Fatal(err)
	}
}
