package model

import "errors"

var (
	ErrNotFound           = errors.New("registro não encontrado")
	ErrConflict           = errors.New("registro já cadastrado")
	ErrInvalidCredentials = errors.New("e-mail ou senha inválidos")
	ErrInvalidCategory    = errors.New("categoria inválida para este usuário")
	ErrInvalidInput       = errors.New("dados inválidos")
)
