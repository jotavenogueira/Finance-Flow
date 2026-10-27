package model

type Transaction struct {
	ID          int             `json:"id"`
	Description string          `json:"description"`
	Amount      float64         `json:"amount"`
	Type        TransactionType `json:"type"`
	Category    Category        `json:"category"`
	Date        string          `json:"date"`
}
