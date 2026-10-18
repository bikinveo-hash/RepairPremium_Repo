"""Diagnostic-only bounded MP4 inspection. No URLs, credentials or media files are exported.
HTTP/container evidence is deliberately separate from decoding and device playback.
"""
import hashlib
import ipaddress
import re
import socket
import struct
import time
import urllib.error
import urllib.parse
import urllib.request

CHUNK = 65536
MAX_CANDIDATES = 12
MAX_REQUESTS = 36
MAX_SECONDS = 150


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs): return None


def origin(url):
    try:
        u=urllib.parse.urlsplit(url)
        if u.scheme!='https' or not u.hostname or u.username or u.password or u.port not in (None,443): return None
        return (u.scheme,u.hostname.lower(),u.port or 443)
    except (ValueError,TypeError): return None


def public_url(url):
    o=origin(url)
    if o is None: return False
    try:
        addresses=socket.getaddrinfo(o[1],443,type=socket.SOCK_STREAM)
        return bool(addresses) and all(ipaddress.ip_address(a[4][0]).is_global for a in addresses)
    except (OSError,ValueError): return False


def boxes(blob):
    """Walk declared box boundaries; partial moov/trak permits bounded header inspection."""
    pos=0
    while pos+8<=len(blob):
        size,typ=struct.unpack_from('>I4s',blob,pos);header=8
        if size==1:
            if pos+16>len(blob): return
            size=struct.unpack_from('>Q',blob,pos+8)[0];header=16
        if size==0: size=len(blob)-pos
        if size<header: return
        yield pos,size,typ,blob[pos+header:min(pos+size,len(blob))]
        pos+=size


def moov_info(blob,tail=False):
    def parse(payload):
        out={'video_track':False,'audio_track':False}
        for _,_,typ,body in boxes(payload):
            if typ==b'mvhd' and len(body)>=20:
                version=body[0];offset=20 if version==1 else 12
                if version not in (0,1) or len(body)<offset+(12 if version==1 else 8): continue
                scale=struct.unpack_from('>I',body,offset)[0]
                ticks=struct.unpack_from('>Q' if version==1 else '>I',body,offset+4)[0]
                if scale and 0<ticks/scale<31*86400: out['duration_seconds']=round(ticks/scale,3)
            if typ==b'trak':
                for _,_,child,content in boxes(body):
                    if child!=b'mdia': continue
                    for _,_,leaf,value in boxes(content):
                        if leaf==b'hdlr' and len(value)>=12:
                            out['video_track'] |= value[8:12]==b'vide'
                            out['audio_track'] |= value[8:12]==b'soun'
        return out
    if not tail:
        for _,_,typ,payload in boxes(blob):
            if typ==b'moov': return parse(payload)
    else:
        # A tail can start within mdat. Accept a complete moov with a valid mvhd only.
        pos=0
        for _ in range(32):
            index=blob.find(b'moov',pos)
            if index<4: break
            size=int.from_bytes(blob[index-4:index],'big');pos=index+4
            if 8<=size<=len(blob)-(index-4):
                parsed=parse(blob[index+4:index-4+size])
                if 'duration_seconds' in parsed: return parsed
    return {}


class MediaProbe:
    def __init__(self,fetch=None,validate_url=public_url,notice=lambda url:False):
        self.opener=urllib.request.build_opener(NoRedirect())
        self.fetch=fetch or self._range
        self.validate_url=validate_url
        self.notice=notice
        self.cache={};self.contexts={};self.requests=0
        self.deadline=time.monotonic()+MAX_SECONDS

    def _range(self,url,start,cookie=None):
        """API Authorization is never accepted. Cookies stay on the initial exact origin."""
        initial=origin(url);target=url
        for _ in range(3):
            if self.requests>=MAX_REQUESTS or time.monotonic()>self.deadline:
                return {'status':'BLOCKED','reason':'RUN_BUDGET'},b''
            if self.notice(target): return {'status':'FAIL','reason':'KNOWN_NOTICE_REDIRECT'},b''
            if not self.validate_url(target): return {'status':'BLOCKED','reason':'NON_PUBLIC_HTTPS_URL'},b''
            headers={'Range':f'bytes={start}-{start+CHUNK-1}', 'Accept-Encoding':'identity',
                     'User-Agent':'Mozilla/5.0 (Android) ReacherDiagnostic/3'}
            if cookie and origin(target)==initial and isinstance(cookie,str) and not re.search(r'[\r\n]',cookie):
                headers['Cookie']=cookie
            self.requests+=1
            try:
                response=self.opener.open(urllib.request.Request(target,headers=headers),timeout=8)
            except urllib.error.HTTPError as error:
                with error:
                    if error.code in (301,302,303,307,308):
                        location=error.headers.get('Location','')
                        if not location: return {'status':'FAIL','http':error.code,'reason':'REDIRECT_WITHOUT_LOCATION'},b''
                        target=urllib.parse.urljoin(target,location);continue
                    return {'status':'FAIL','http':error.code,'reason':'MEDIA_HTTP_ERROR'},b''
            except Exception:
                return {'status':'BLOCKED','reason':'MEDIA_NETWORK_ERROR'},b''
            with response:
                status=response.status
                if status not in (200,206): return {'status':'FAIL','http':status,'reason':'MEDIA_HTTP_ERROR'},b''
                # read is capped even when a CDN ignores Range and advertises a whole movie.
                raw=response.read(CHUNK)
                content_range=response.headers.get('Content-Range','')
                match=re.fullmatch(r'bytes (\d+)-(\d+)/(\d+)',content_range)
                total=int(match[3]) if match else None
                if status==206 and (not match or int(match[1])!=start or int(match[2])<start):
                    return {'status':'FAIL','http':status,'reason':'RANGE_MISMATCH'},b''
                if status==200 and start:
                    return {'status':'BLOCKED','http':status,'reason':'RANGE_IGNORED'},b''
                if status==200:
                    length=response.headers.get('Content-Length','')
                    if length.isdigit():total=int(length)
                ct=response.headers.get('Content-Type','').split(';')[0].strip().lower()
                return {'status':'PASS','http':status,'bytes_read':len(raw),'total_bytes':total,
                        'range_honored':status==206,
                        'content_type':ct if ct in ('video/mp4','application/octet-stream','binary/octet-stream','text/html','application/json') else 'OTHER'},raw
        return {'status':'BLOCKED','reason':'REDIRECT_LIMIT'},b''

    def inspect(self,url,cookie):
        first,raw=self.fetch(url,0,cookie)
        out={'http_checks':[first],'decode':'NOT_TESTED','device_playback':'NOT_TESTED'}
        if first.get('status')!='PASS': return {**out,'status':first.get('status','BLOCKED'),'reason':first.get('reason','HTTP_FAILURE')}
        if first.get('content_type') in ('text/html','application/json') or raw.lstrip()[:1] in (b'<',b'{',b'['):
            return {**out,'status':'FAIL','reason':'NON_MEDIA_RESPONSE'}
        if len(raw)<12 or raw[4:8]!=b'ftyp':
            return {**out,'status':'NOT_TESTED','reason':'NOT_SUPPORTED_MP4_HEADER'}
        out['sample_sha256']=hashlib.sha256(raw).hexdigest()
        info=moov_info(raw);total=first.get('total_bytes')
        # Jump past a large mdat using its box size rather than downloading it.
        next_box=None
        for pos,size,typ,_ in boxes(raw):
            if typ==b'mdat' and pos+size>=len(raw): next_box=pos+size;break
        offsets=[]
        if isinstance(total,int) and total>CHUNK:
            if next_box is not None and CHUNK<=next_box<total: offsets.append(next_box)
            offsets.append(max(CHUNK,total-CHUNK))
        for start in dict.fromkeys(offsets):
            if info.get('duration_seconds') and info.get('video_track') and info.get('audio_track'): break
            result,part=self.fetch(url,start,cookie);out['http_checks'].append(result)
            if result.get('status')=='PASS':
                parsed=moov_info(part,tail=start!=next_box)
                if parsed: info=parsed
        out.update(info)
        duration=info.get('duration_seconds')
        if duration is not None and duration<300:
            return {**out,'status':'FAIL','reason':'SHORT_MEDIA_NOT_VERIFIED_EPISODE'}
        if duration is not None and info.get('video_track') and info.get('audio_track'):
            return {**out,'status':'PASS','reason':'MP4_HTTP_CONTAINER_AND_LENGTH_ONLY'}
        return {**out,'status':'BLOCKED','reason':'MP4_METADATA_INCOMPLETE_WITHIN_BYTE_BUDGET'}

    def check(self,url,cookie,sid,se,ep,source,resource_id,notice,restricted=False):
        context={'subjectId':sid,'se':se,'ep':ep,'source':source,'resourceId':resource_id}
        if restricted: return {**context,'status':'BLOCKED','reason':'MEMBERSHIP_OR_ACCESS_MARKER'}
        if not isinstance(url,str) or origin(url) is None: return {**context,'status':'BLOCKED','reason':'INVALID_OR_NON_HTTPS_URL'}
        if notice(url): return {**context,'status':'FAIL','reason':'KNOWN_NOTICE'}
        # Exact URL AND exact cookie; signed query variants are never conflated.
        key=(url,cookie if isinstance(cookie,str) else '')
        if key not in self.cache:
            if len(self.cache)>=MAX_CANDIDATES or time.monotonic()>self.deadline:
                return {**context,'status':'BLOCKED','reason':'RUN_BUDGET'}
            label='media-'+str(len(self.cache)+1)
            try: result=self.inspect(url,cookie)
            except Exception: result={'status':'BLOCKED','reason':'MEDIA_INSPECTION_ERROR'}
            self.cache[key]={'media_key':label,**result}
            self.contexts[label]=[]
        saved=self.cache[key];self.contexts[saved['media_key']].append(context)
        return {**context,**saved}

    def summary(self):
        reused=[]
        for label,contexts in self.contexts.items():
            if len({(c['subjectId'],c['se'],c['ep']) for c in contexts})>1:
                reused.append({'media_key':label,'contexts':contexts,'warning':'SAME_URL_ACROSS_EPISODES_OR_AUDIO_SUBJECTS'})
        return {'unique_candidates_checked':len(self.cache),'http_requests':self.requests,
                'max_candidates':MAX_CANDIDATES,'max_requests':MAX_REQUESTS,
                'max_bytes_per_response':CHUNK,'max_seconds':MAX_SECONDS,
                'shared_media':reused,'decode':'NOT_TESTED','device_playback':'NOT_TESTED'}
