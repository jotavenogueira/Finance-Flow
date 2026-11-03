package model

type Transaction struct {
	ID          int             `json:"id"`
	Description string          `json:"description"`
	Amount      float64         `json:"amount"`
	Type        TransactionType `json:"type"`
	CategoryID  int             `json:"category_id"`
	Category    string          `json:"category"`
	Date        string          `json:"date"`
}
