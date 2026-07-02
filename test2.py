import urllib.request, json
try:
    req = urllib.request.Request('https://pawchive.st/api/v1/creators', headers={'User-Agent': 'Mozilla/5.0'})
    creators = json.loads(urllib.request.urlopen(req).read().decode('utf-8'))
    for c in creators[:20]:
        req2 = urllib.request.Request(f'https://pawchive.st/api/v1/{c["service"]}/user/{c["id"]}', headers={'User-Agent': 'Mozilla/5.0'})
        posts = json.loads(urllib.request.urlopen(req2).read().decode('utf-8'))
        for p in posts:
            if p.get('file') and p['file'].get('path'):
                print(f"Post ID: {p['id']}, Service: {p['service']}, User: {p['user']}")
                print(f"File Path: {p['file']['path']}")
                exit(0)
            if p.get('attachments') and len(p['attachments']) > 0 and p['attachments'][0].get('path'):
                print(f"Post ID: {p['id']}, Service: {p['service']}, User: {p['user']}")
                print(f"Attachment Path: {p['attachments'][0]['path']}")
                exit(0)
except Exception as e:
    print('Error:', e)
