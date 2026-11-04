package model

import "errors"

var (
	ErrNotFound              = errors.New("registro não encontrado")
	ErrConflict              = errors.New("registro já cadastrado")
	ErrInvalidCredentials    = errors.New("e-mail ou senha inválidos")
	ErrInvalidCategory       = errors.New("categoria inválida para este usuário")
	ErrInvalidInput          = errors.New("dados inválidos")
	ErrGoogleEmailMismatch   = errors.New("use uma conta Google com o mesmo e-mail da sua conta FinanceFlow")
	ErrGoogleAccountExists   = errors.New("já existe uma conta com este e-mail; entre com sua senha para conectar o Google")
	ErrGoogleAccountConflict = errors.New("esta conta Google já está conectada a outra conta, ou o usuário já conectou outra conta Google")
)
