import io
import json
from pathlib import Path
import socket
import struct
import unittest
from unittest.mock import patch
import urllib.error

import reacher_diagnostic as kit
import reacher_media_probe as media


def box(kind,body): return struct.pack('>I4s',len(body)+8,kind)+body


def movie(seconds=3000,version=0):
    hdr=(b'\x01\0\0\0'+b'\0'*16+struct.pack('>IQ',1000,int(seconds*1000))) if version else (b'\0'*12+struct.pack('>II',1000,int(seconds*1000)))
    tracks=b''.join(box(b'trak',box(b'mdia',box(b'hdlr',b'\0'*8+kind+b'\0'*12))) for kind in (b'vide',b'soun'))
    return box(b'ftyp',b'isom'+b'\0'*4)+box(b'moov',box(b'mvhd',hdr)+tracks)


class Response(io.BytesIO):
    def __init__(self,blob,status=206,headers=None):
        super().__init__(blob);self.status=status;self.headers=headers or {}


class Opener:
    def __init__(self,respond): self.calls=[];self.respond=respond
    def open(self,request,timeout):
        self.calls.append(request);return self.respond(request,len(self.calls))


class MediaTests(unittest.TestCase):
    def phone_v3(self):
        return json.loads((Path(__file__).parent/'phone_reacher_media_20261016.json').read_text())

    def test_real_phone_v3_all_native_resource_candidates_share_short_asset(self):
        d=self.phone_v3()
        self.assertEqual(len(d['episode_requests']),8)
        candidates=[c for r in d['episode_requests'] for c in r['candidates']]
        self.assertEqual(len(candidates),10)
        self.assertEqual({c['media_key'] for c in candidates},{'media-1'})
        self.assertTrue(all(r['http']==200 and r['code']=='0' for r in d['episode_requests']))
        observation=d['media_observations'][0]
        self.assertEqual(observation['status'],'FAIL')
        self.assertEqual(observation['reason'],'SHORT_MEDIA_NOT_VERIFIED_EPISODE')
        self.assertEqual(observation['duration_seconds'],22.708)
        self.assertEqual(observation['http_checks'][0]['total_bytes'],824720)
        self.assertEqual(d['media_probe']['http_requests'],1)
        self.assertEqual(d['media_probe']['unique_candidates_checked'],1)
        self.assertEqual(d['device_playback'],'NOT_TESTED')

    def test_real_phone_v3_exact_episode_resource_ids_are_not_playback_proof(self):
        d=self.phone_v3()
        resources=[r for r in d['episode_requests'] if r['endpoint']=='resource']
        expected={(kit.SEED,1):'8792116150390911208',(kit.SEED,2):'2342961483996361232',
                  ('4149489342181954296',1):'2889787666332115640',
                  ('4149489342181954296',2):'4488565534048641720'}
        for r in resources:
            self.assertTrue(r['exact_subject'])
            self.assertEqual(r['requested_se'],1)
            self.assertEqual(r['exact_episode_resources'],1)
            item=r['candidates'][0]
            self.assertEqual(item['resourceId'],expected[r['subjectId'],r['requested_ep']])
            self.assertGreater(item['duration_raw'],3000)
            self.assertEqual(item['size_raw'],'824720')
            # Resource catalog duration does not describe the actual returned short MP4.
            self.assertGreater(item['duration_raw'],d['media_observations'][0]['duration_seconds'])
        contexts=d['media_probe']['shared_media'][0]['contexts']
        self.assertEqual({(c['subjectId'],c['se'],c['ep']) for c in contexts},
                         {(sid,1,ep) for sid in kit.REACHER_IDS for ep in (1,2)})

    def test_observed_short_duration_is_rejected_with_synthetic_mp4_header(self):
        # Header bytes are synthetic; the duration value comes from the real phone report.
        # This regression proves parser rejection, never actual Reacher playback.
        seconds=self.phone_v3()['media_observations'][0]['duration_seconds']
        p=media.MediaProbe(fetch=lambda *a:({'status':'PASS','http':206},movie(seconds=seconds)))
        out=p.inspect('https://fixture.test/episode',None)
        self.assertEqual(out['status'],'FAIL')
        self.assertEqual(out['reason'],'SHORT_MEDIA_NOT_VERIFIED_EPISODE')
        self.assertAlmostEqual(out['duration_seconds'],seconds,places=3)
        self.assertEqual(out['device_playback'],'NOT_TESTED')

    def test_real_phone_fixture_excludes_session_and_media_urls(self):
        text=json.dumps(self.phone_v3()).lower()
        for forbidden in ('https://','http://','authorization','bearer ','cloudfront-policy',
                          'signcookie','x-tr-signature','password'):
            self.assertNotIn(forbidden,text)

    def test_actual_phone_v2_proves_candidate_metadata_only(self):
        d=json.loads((Path(__file__).parent/'phone_reacher_native_20261016.json').read_text())
        native=[r for r in d['probe']['requests'] if r['endpoint']=='play-info']
        self.assertEqual([r['data']['streams_count'] for r in native],[2,1])
        self.assertTrue(all(r['http']==200 and r['code']=='0' for r in native))
        self.assertEqual(d['probe']['media_validation'],'NOT_TESTED')
        # V2 did not inspect @SerializedName("list"): its zero is not evidence of no resource.
        resources=[r for r in d['probe']['requests'] if r['endpoint']=='resource']
        self.assertTrue(all('list' not in r['data']['schema'] for r in resources))

    def test_apk_serialized_resource_list_and_link(self):
        raw={'subjectId':kit.SEED,'list':[{'resourceId':'r1','se':1,'ep':1,'resourceLink':'https://cdn.test/a?Signature=PRIVATE','duration':3000}],
             'items':[{'resourceId':'wrong'}]}
        out=kit.summary(raw)
        self.assertEqual(out['resources'][0]['resourceId'],'r1')
        self.assertTrue(out['resources'][0]['url_present'])
        self.assertEqual(out['resources'][0]['duration_raw'],3000)
        self.assertNotIn('PRIVATE',json.dumps(out))

    def test_missing_resource_array_is_unknown_not_empty(self):
        class Guest:
            def get(self,path,q):
                if path.endswith('season-info'):return {'http':200},{'seasons':[{'se':1,'maxEp':1}]}
                return {'http':200},{'subjectId':kit.SEED}
        report=kit.probe(Guest(),[(kit.SEED,1,1)])
        resource=report['requests'][-1]
        self.assertEqual(resource['exact_episode_resources'],'UNKNOWN')
        self.assertFalse(resource['resource_array_present'])

    def test_mp4_v0_and_v1_container_is_not_device_playback(self):
        for version in (0,1):
            blob=movie(version=version)
            p=media.MediaProbe(fetch=lambda *a:({'status':'PASS','http':206},blob))
            out=p.check('https://cdn.test/episode.mp4',None,kit.SEED,1,1,'native','id',kit.known_notice)
            self.assertEqual(out['status'],'PASS')
            self.assertEqual(out['duration_seconds'],3000)
            self.assertTrue(out['audio_track'] and out['video_track'])
            self.assertEqual(out['device_playback'],'NOT_TESTED');self.assertEqual(out['decode'],'NOT_TESTED')

    def test_short_mp4_unknown_notice_rejected_by_duration(self):
        p=media.MediaProbe(fetch=lambda *a:({'status':'PASS','http':206},movie(seconds=7)))
        out=p.check('https://cdn.test/not-a-known-notice.mp4',None,kit.SEED,1,1,'native','id',kit.known_notice)
        self.assertEqual(out['reason'],'SHORT_MEDIA_NOT_VERIFIED_EPISODE')
        self.assertEqual(out['status'],'FAIL')

    def test_html_json_and_http_errors_cannot_be_media(self):
        for http,blob,reason in [({'status':'PASS'},b'<html>upgrade</html>','NON_MEDIA_RESPONSE'),
                                ({'status':'PASS'},b'{"error":1}','NON_MEDIA_RESPONSE'),
                                ({'status':'FAIL','http':403,'reason':'MEDIA_HTTP_ERROR'},b'','MEDIA_HTTP_ERROR')]:
            p=media.MediaProbe(fetch=lambda *a:(http,blob))
            self.assertEqual(p.inspect('https://cdn.test/video',None)['reason'],reason)

    def test_budget_and_known_notice_do_not_make_http_requests(self):
        p=media.MediaProbe(fetch=lambda *a:(_ for _ in ()).throw(AssertionError('fetch')))
        self.assertEqual(p.check('https://cdn.test/notice.mp4',None,kit.SEED,1,1,'native','id',kit.known_notice)['reason'],'KNOWN_NOTICE')
        self.assertEqual(p.check('https://cdn.test/a',None,kit.SEED,1,1,'native','id',kit.known_notice,restricted=True)['reason'],'MEMBERSHIP_OR_ACCESS_MARKER')
        p.deadline=0
        self.assertEqual(p.check('https://cdn.test/a',None,kit.SEED,1,1,'native','id',kit.known_notice)['reason'],'RUN_BUDGET')

    def test_episode_shared_url_is_flagged_without_exporting_it(self):
        p=media.MediaProbe(fetch=lambda *a:({'status':'PASS'},movie()))
        url='https://cdn.test/a?Signature=PRIVATE'
        for ep in (1,2): p.check(url,'PRIVATE_COOKIE',kit.SEED,1,ep,'native','id',kit.known_notice)
        out=p.summary()
        self.assertEqual(out['unique_candidates_checked'],1)
        self.assertEqual(len(out['shared_media']),1)
        self.assertNotIn('PRIVATE',json.dumps(out));self.assertNotIn('https:',json.dumps(out))
        p.check(url+'2','PRIVATE_COOKIE',kit.SEED,1,1,'native','id',kit.known_notice)
        p.check(url,'DIFFERENT_COOKIE',kit.SEED,1,1,'native','id',kit.known_notice)
        self.assertEqual(len(p.cache),3)

    def test_redirect_strips_cookie_across_origins_and_never_api_auth(self):
        def respond(req,n):
            if n==1: raise urllib.error.HTTPError(req.full_url,302,'redirect',{'Location':'https://other.test/file?Signature=PRIVATE'},None)
            return Response(b'part',headers={'Content-Range':'bytes 0-3/4'})
        p=media.MediaProbe(validate_url=lambda u:True);p.opener=Opener(respond)
        result,blob=p._range('https://cdn.test/a',0,'SECRET_COOKIE')
        self.assertEqual(result['status'],'PASS')
        self.assertEqual(p.opener.calls[0].get_header('Cookie'),'SECRET_COOKIE')
        self.assertIsNone(p.opener.calls[1].get_header('Cookie'))
        self.assertTrue(all(r.get_header('Authorization') is None for r in p.opener.calls))
        self.assertNotIn('PRIVATE',json.dumps(result))

    def test_redirect_to_known_notice_rejected(self):
        def respond(req,n):raise urllib.error.HTTPError(req.full_url,302,'redirect',{'Location':'/notice.mp4'},None)
        p=media.MediaProbe(validate_url=lambda u:True,notice=kit.known_notice);p.opener=Opener(respond)
        result,_=p._range('https://cdn.test/a',0)
        self.assertEqual(result['reason'],'KNOWN_NOTICE_REDIRECT');self.assertEqual(len(p.opener.calls),1)

    def test_range_ignored_is_still_bounded_and_offset_cannot_be_faked(self):
        p=media.MediaProbe(validate_url=lambda u:True)
        p.opener=Opener(lambda r,n:Response(b'x'*(media.CHUNK*2),200,{'Content-Length':str(10**9)}))
        result,blob=p._range('https://cdn.test/a',0)
        self.assertEqual(len(blob),media.CHUNK);self.assertFalse(result['range_honored'])
        result,blob=p._range('https://cdn.test/a',media.CHUNK)
        self.assertEqual(result['reason'],'RANGE_IGNORED');self.assertEqual(blob,b'')
        p.opener=Opener(lambda r,n:Response(b'x',206,{'Content-Range':'bytes 0-0/1000000'}))
        self.assertEqual(p._range('https://cdn.test/a',media.CHUNK)[0]['reason'],'RANGE_MISMATCH')

    def test_non_public_and_non_https_urls_are_rejected(self):
        for url in ('http://cdn.test/a','https://user:pass@cdn.test/a','https://cdn.test:8443/a','https://['):
            self.assertIsNone(media.origin(url))
        for address in ('127.0.0.1','10.0.0.1','169.254.169.254','::1'):
            with patch.object(socket,'getaddrinfo',return_value=[(2,1,6,'',(address,443))]):
                self.assertFalse(media.public_url('https://cdn.test/a'))

    def test_partial_metadata_stays_blocked(self):
        p=media.MediaProbe(fetch=lambda *a:({'status':'PASS'},box(b'ftyp',b'isom0000')))
        self.assertEqual(p.inspect('https://cdn.test/a',None)['reason'],'MP4_METADATA_INCOMPLETE_WITHIN_BYTE_BUDGET')
        # Tail scan starts within mdat and only accepts an actual complete moov with mvhd.
        self.assertEqual(media.moov_info(b'x'*50+movie()[16:],tail=True)['duration_seconds'],3000)

    def test_mdat_jump_fetches_metadata_without_downloading_video(self):
        head=box(b'ftyp',b'isom0000')+struct.pack('>I4s',100000,b'mdat')+b'x'*100
        moov=movie()[16:];calls=[]
        def fetch(url,start,cookie):
            calls.append(start)
            return ({'status':'PASS','total_bytes':100016+len(moov)},head if start==0 else moov)
        out=media.MediaProbe(fetch=fetch).inspect('https://cdn.test/a',None)
        self.assertEqual(out['status'],'PASS');self.assertEqual(calls,[0,100016])

    def test_resource_mapping_validated_only_for_exact_subject_and_episode(self):
        from test_reacher_diagnostic import FakeGuest
        def respond(path,q):
            if path.endswith('get'):return {'http':200},{'subjectId':q['subjectId']}
            if path.endswith('season-info'):return {'http':200},{'seasons':[{'se':1,'allEp':'1,2'}]}
            if path.endswith('resource'):return {'http':200},{'subjectId':q['subjectId'],'list':[
                {'resourceId':'right','se':1,'ep':q['epFrom'],'resourceLink':'https://cdn.test/right'+str(q['epFrom'])},
                {'resourceId':'wrong','se':1,'ep':99,'resourceLink':'https://cdn.test/wrong'}]}
            return {'http':200},{}
        p=media.MediaProbe(fetch=lambda *a:({'status':'PASS'},movie()))
        out=kit.probe(FakeGuest(respond),[(kit.SEED,1,1)],inspect_playback=True,media=p)
        checks=[c for r in out['requests'] for c in r.get('resource_media_checks',[])]
        self.assertEqual([c['ep'] for c in checks],[1,2])
        self.assertTrue(all(c['resourceId']=='right' for c in checks))
        self.assertEqual(out['device_playback'],'NOT_TESTED')

    def test_mismatched_native_identity_prevents_fetch(self):
        from test_reacher_diagnostic import FakeGuest
        def respond(path,q):
            if path.endswith('get'):return {'http':200},{'subjectId':q['subjectId']}
            if path.endswith('season-info'):return {'http':200},{'seasons':[{'se':1,'allEp':'1,2'}]}
            if path.endswith('play-info'):return {'http':200},{'subjectId':'999','streams':[{'url':'https://cdn.test/wrong'}]}
            return {'http':200},{}
        p=media.MediaProbe(fetch=lambda *a:(_ for _ in ()).throw(AssertionError('fetch')))
        out=kit.probe(FakeGuest(respond),[(kit.SEED,1,1)],inspect_playback=True,media=p)
        self.assertEqual(p.summary()['unique_candidates_checked'],0)
        self.assertTrue(all(r['response_identity']=='MISMATCH' for r in out['requests'] if r['endpoint']=='play-info'))

if __name__=='__main__':unittest.main()
