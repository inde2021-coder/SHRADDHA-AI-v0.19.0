# AKKPS Runner command language

The local parser accepts semicolon/newline separated `field=value` or `field: value` assignments. Submit intent is explicit and may be expressed as submit/save/send or common Hindi phrases such as जमा करें, सबमिट करें and भेजें.

Malformed segments, overlong commands, repeated fields and conflicting values are returned as issues. If any issue exists, the Runner does not fill the form. It never invents missing values or silently picks one of two conflicting values.

Example:
```
नाम=राम; गाँव=रामपुर; मोबाइल=9876543210; जमा करें
```

The form mapper still requires exact visible field matches and a separate final submit confirmation.
