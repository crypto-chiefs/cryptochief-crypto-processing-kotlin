# Examples

| File | What it shows |
| ---- | ------------- |
| [payout/PayoutExample.kt](payout/PayoutExample.kt) | Estimate, execute, and poll a payout |
| [invoice/InvoiceExample.kt](invoice/InvoiceExample.kt) | Create a fiat or crypto invoice and poll until paid |
| [webhook/WebhookExample.kt](webhook/WebhookExample.kt) | JDK HTTP server verifying webhook signatures |
| [ton-jetton/TonJettonExample.kt](ton-jetton/TonJettonExample.kt) | Jetton (USDT-on-TON) transfer |

Each example reads credentials from the environment:

```
export CRYPTO_CHIEF_MERCHANT_ID=mer_...
export CRYPTO_CHIEF_API_KEY=sk_...
```
