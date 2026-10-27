package model

type TransactionType string

const (
	TypeIncome  TransactionType = "income"
	TypeExpense TransactionType = "expense"
)

type Category string

const (
	CategorySalary        Category = "salary"
	CategoryFreelance     Category = "freelance"
	CategoryHousing       Category = "housing"
	CategoryFood          Category = "food"
	CategoryTransport     Category = "transport"
	CategoryLeisure       Category = "leisure"
	CategorySubscriptions Category = "subscriptions"
	CategoryOther         Category = "other"
)
