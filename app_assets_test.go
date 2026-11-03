package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestDashboardAssetsAreEmbeddedAndServed(t *testing.T) {
	handler := NewHandler(nil)
	for _, test := range []struct {
		path, contentType, fragment string
	}{
		{"/", "text/html", `/static/dashboard.js`},
		{"/static/dashboard.js", "text/javascript", "FinanceDashboard"},
		{"/static/app.js", "text/javascript", "renderDashboard"},
	} {
		t.Run(test.path, func(t *testing.T) {
			response := httptest.NewRecorder()
			handler.ServeHTTP(response, httptest.NewRequest(http.MethodGet, test.path, nil))
			if response.Code != http.StatusOK {
				t.Fatalf("status = %d; want 200", response.Code)
			}
			if !strings.Contains(response.Header().Get("Content-Type"), test.contentType) {
				t.Fatalf("unexpected content type: %s", response.Header().Get("Content-Type"))
			}
			if !strings.Contains(response.Body.String(), test.fragment) {
				t.Fatalf("response missing dashboard asset content %q", test.fragment)
			}
		})
	}
}
