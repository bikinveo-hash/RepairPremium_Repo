#!/usr/bin/env python3
"""Optional live Moviebox catalog/H5 probe. Prints counts/signatures, never credentials or media URLs.
Not a deterministic CI test or an Android playback test. Uses the observed public metadata fixture.
"""
import json
from pathlib import Path
import urllib.request
import urllib.error
import urllib.parse

meta=json.loads((Path(__file__).parent/'fixtures/insidious-public-detail.json').read_text())['data']
host='https://h5-api.aoneroom.com'
ua='Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36'
params={'subjectId':meta['subjectId'],'se':0,'ep':0,'detailPath':meta['detailPath'],
        'streamSignType':1,'supportCodecs[hevc]':1,'supportCodecs[h264]':1}
referer=host+'/movies/'+meta['detailPath']+'?id='+meta['subjectId']+'&type=/movie/detail&detailSe=&detailEp=&lang=en'
headers={'User-Agent':ua,'Referer':referer,'Accept':'application/json',
         'x-client-info':'{"timezone":"Asia/Calcutta"}','x-request-lang':'en','x-source':''}
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs): return None
opener=urllib.request.build_opener(NoRedirect)
try:
    with opener.open(urllib.request.Request(host+'/wefeed-h5api-bff/subject/play?'+urllib.parse.urlencode(params),headers=headers),timeout=12) as response:
        root=json.load(response)
    assert root.get('code')==0, 'H5 application error'
    data=root.get('data') or {}
    assert str(data.get('subjectId',meta['subjectId']))==meta['subjectId'], 'Wrong subject'
    counts={k:len(data.get(k) or []) for k in ['dash','hls','streams']}
    print('H5 subject',meta['subjectId'],'HTTP 200; candidate groups',counts,flush=True)
    assert any(counts.values()), 'No H5 candidates'
    stream=next((s for s in data.get('streams',[]) if str(s.get('url','')).startswith('https://')),None)
    if stream:
        h={'User-Agent':ua,'Referer':referer,'Origin':host,'Accept':'*/*','Range':'bytes=0-4095'}
        cookie=stream.get('signCookie') or data.get('signCookie')
        if cookie and '\r' not in cookie and '\n' not in cookie:
            h['Cookie']=cookie
            key=stream.get('signHeaderKey') or data.get('signHeaderKey') or 'X-MB-Token'
            import re
            if re.fullmatch('X-[A-Za-z0-9-]+',key,re.I) and not key.lower().startswith('x-forwarded'):h[key]=cookie
        try:
            # No redirects: signed cookies cannot escape the original resource host/path.
            with opener.open(urllib.request.Request(stream['url'],headers=h),timeout=12) as response:
                body=response.read(4096);code=response.status
            kind='ISO-BMFF' if body[4:8] in [b'ftyp',b'styp',b'moof',b'sidx',b'moov',b'mdat'] else 'UNVERIFIED'
            print('Direct resource HTTP',code,'sample bytes',len(body),'signature',kind,flush=True)
        except urllib.error.HTTPError as e:print('Direct resource BLOCKED HTTP',e.code,flush=True)
        except Exception as e:print('Direct resource BLOCKED',type(e).__name__,flush=True)
    print('Manifest/segment playback and Android device: NOT_TESTED',flush=True)
except urllib.error.HTTPError as e:
    print('Live probe BLOCKED HTTP',e.code);raise SystemExit(1)
except Exception as e:
    print('Live probe FAILED',type(e).__name__);raise SystemExit(1)
