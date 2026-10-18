#!/usr/bin/env python3
"""Optional live H5 response probe. Response PASS does not prove CDN/device playback.
Uses public identity fixtures only; never logs/stores credentials or signed media URLs.
"""
import concurrent.futures
import json
from pathlib import Path
import urllib.parse
import urllib.request

FIXTURES = Path(__file__).with_name('fixtures')
def probe(case):
    filename,se,ep = case
    meta=json.loads((FIXTURES/(filename+'-public-detail.json')).read_text())['data']
    subject,path=meta['subjectId'],meta['detailPath']
    params={'subjectId':subject,'se':se,'ep':ep,'detailPath':path,'streamSignType':1,
            'supportCodecs[hevc]':1,'supportCodecs[h264]':1}
    host='https://h5-api.aoneroom.com'
    headers={'User-Agent':'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36',
        'Referer':f'{host}/movies/{path}?id={subject}&type=/movie/detail&detailSe=&detailEp=&lang=en',
        'Accept':'application/json','x-client-info':'{"timezone":"Asia/Calcutta"}','x-request-lang':'en','x-source':''}
    result={'case':filename,'subject':subject,'se':se,'ep':ep,'media_validation':'NOT_TESTED','device':'NOT_TESTED'}
    try:
        request=urllib.request.Request(host+'/wefeed-h5api-bff/subject/play?'+urllib.parse.urlencode(params),headers=headers)
        with urllib.request.urlopen(request,timeout=20) as response:
            result['http']=response.status
            root=json.load(response)
        data=root.get('data') or {}
        consistent=all(k not in data or str(data[k])==str(params[k]) for k in ['subjectId','se','ep'])
        groups={k:len(data.get(k) or []) for k in ['dash','hls','streams']}
        result.update(code=root.get('code'),groups=groups,response_identity_consistent=consistent,
            h5_response='PASS' if root.get('code')==0 and consistent and any(groups.values()) else 'EMPTY_OR_REJECTED')
    except Exception as error:
        result.update(h5_response='BLOCKED',error=type(error).__name__)
    return result

if __name__=='__main__':
    cases=[('spiderman',0,0),('hotd',1,1),('hotd',2,4),('insidious',0,0)]
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        for result in pool.map(probe,cases):print(json.dumps(result),flush=True)
