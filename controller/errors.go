package controller

import (
	"errors"
	"log"
	"net/http"

	"github.com/gin-gonic/gin"
	"github.com/jotavenogueira/Finance-Flow/model"
)

func respondError(c *gin.Context, err error) {
	status, message := http.StatusInternalServerError, "Não foi possível concluir a operação."
	switch {
	case errors.Is(err, model.ErrInvalidInput):
		status, message = http.StatusBadRequest, err.Error()
	case errors.Is(err, model.ErrInvalidCategory):
		status, message = http.StatusBadRequest, model.ErrInvalidCategory.Error()
	case errors.Is(err, model.ErrInvalidCredentials):
		status, message = http.StatusUnauthorized, model.ErrInvalidCredentials.Error()
	case errors.Is(err, model.ErrNotFound):
		status, message = http.StatusNotFound, model.ErrNotFound.Error()
	case errors.Is(err, model.ErrConflict):
		status, message = http.StatusConflict, "Já existe um cadastro com esses dados."
	default:
		// Não registrar payloads, credenciais ou detalhes de conexão.
		log.Print("FinanceFlow: falha interna durante operação HTTP")
	}
	c.AbortWithStatusJSON(status, gin.H{"message": message})
}
