#!/usr/bin/env python3
"""Sign a synthetic pending-payment callback. No real provider protocol or secrets."""
import argparse, hashlib, hmac, json, os, time, urllib.request, uuid
parser=argparse.ArgumentParser()
parser.add_argument('payment_id'); parser.add_argument('--session',default='meridian-rehearsal')
parser.add_argument('--provider',choices=['adyen','worldpay'],default='adyen')
parser.add_argument('--status',choices=['completed','declined'],default='completed')
parser.add_argument('--base-url',default='http://127.0.0.1:8080/api/v1')
parser.add_argument('--event-id',default=None)
args=parser.parse_args();secret=os.environ.get('MERIDIAN_WEBHOOK_SECRET')
if not secret: raise SystemExit('Set MERIDIAN_WEBHOOK_SECRET to the same synthetic secret used by the server.')
raw=json.dumps({'sessionId':args.session,'eventId':args.event_id or str(uuid.uuid4()),'paymentId':args.payment_id,'status':args.status},separators=(',',':')).encode()
ts=str(int(time.time()));signature=hmac.new(secret.encode(),ts.encode()+b'.'+raw,hashlib.sha256).hexdigest()
request=urllib.request.Request(args.base_url.rstrip('/')+'/webhooks/'+args.provider,data=raw,headers={'Content-Type':'application/json','X-Webhook-Timestamp':ts,'X-Meridian-Signature':signature},method='POST')
with urllib.request.urlopen(request,timeout=15) as response: print(response.status,response.read().decode())
