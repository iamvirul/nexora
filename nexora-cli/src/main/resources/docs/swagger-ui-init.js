// Kept out of index.html so the page runs under a CSP that allows no inline scripts.
window.ui = SwaggerUIBundle({
  url: '/openapi.yaml',
  dom_id: '#swagger-ui',
  deepLinking: true,
  // Swagger UI otherwise sends the document to validator.swagger.io; keep every request local.
  validatorUrl: null,
});
