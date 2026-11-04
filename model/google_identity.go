package model

// GoogleIdentity contains only claims already verified by the OpenID Connect layer.
// Subject is the stable Google account identifier; email is not its identifier.
type GoogleIdentity struct {
	Subject string
	Email   string
	Name    string
}
