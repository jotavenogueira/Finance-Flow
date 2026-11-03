package main

import (
	"database/sql"
	"embed"
	"encoding/json"
	"net/http"

	"github.com/gin-gonic/gin"
	"github.com/jotavenogueira/Finance-Flow/controller"
	"github.com/jotavenogueira/Finance-Flow/repository"
	"github.com/jotavenogueira/Finance-Flow/usecase"
)

// A tela acompanha o binário, inclusive na imagem Docker sem fontes.
//
//go:embed web/index.html web/app.js web/style.css
var webFiles embed.FS

func NewHandler(connection *sql.DB) http.Handler {
	router := gin.New()
	router.Use(gin.Logger(), gin.Recovery())
	_ = router.SetTrustedProxies(nil)
	router.Use(func(c *gin.Context) {
		c.Header("Cache-Control", "no-store")
		c.Header("X-Content-Type-Options", "nosniff")
		c.Header("X-Frame-Options", "DENY")
		c.Header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'")
		c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, 1<<20)
		c.Next()
	})
	assets := map[string]struct{ file, contentType string }{
		"/":                 {"web/index.html", "text/html; charset=utf-8"},
		"/static/app.js":    {"web/app.js", "text/javascript; charset=utf-8"},
		"/static/style.css": {"web/style.css", "text/css; charset=utf-8"},
	}
	for path, asset := range assets {
		data, err := webFiles.ReadFile(asset.file)
		if err != nil {
			panic(err)
		}
		contentType := asset.contentType
		router.GET(path, func(c *gin.Context) { c.Data(http.StatusOK, contentType, data) })
	}
	router.GET("/ping", func(c *gin.Context) { c.JSON(http.StatusOK, gin.H{"message": "pong"}) })

	authRepository := repository.NewAuthRepository(connection)
	authUsecase := usecase.NewAuthUsecase(authRepository)
	authController := controller.NewAuthController(authUsecase)
	router.POST("/auth/register", authController.Register)
	router.POST("/auth/login", authController.Login)

	protected := router.Group("")
	protected.Use(authController.RequireAuth)
	protected.GET("/auth/me", authController.Me)
	protected.POST("/auth/logout", authController.Logout)

	categoryRepository := repository.NewCategoryRepository(connection)
	categoryUsecase := usecase.NewCategoryUsecase(&categoryRepository)
	categoryController := controller.NewCategoryController(categoryUsecase)
	protected.GET("/categories", categoryController.GetCategories)
	protected.POST("/categories", categoryController.CreateCategory)

	transactionRepository := repository.NewTransactionRepository(connection)
	transactionUsecase := usecase.NewTransactionUsecase(&transactionRepository)
	transactionController := controller.NewTransactionController(transactionUsecase)
	protected.GET("/transactions", transactionController.GetTransactions)
	protected.POST("/transactions", transactionController.CreateTransaction)
	protected.GET("/transactions/:transactionId", transactionController.GetTransactionsById)
	protected.DELETE("/transactions/:transactionId", transactionController.DeleteTransaction)

	protection := http.NewCrossOriginProtection()
	protection.SetDenyHandler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusForbidden)
		_ = json.NewEncoder(w).Encode(map[string]string{"message": "Origem da requisição não permitida."})
	}))
	return protection.Handler(router)
}
