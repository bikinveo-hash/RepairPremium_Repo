import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import reacher_diagnostic as kit


class FakeGuest:
    def __init__(self, responder): self.responder=responder; self.calls=[]
    def get(self,path,params):
        self.calls.append((path,params))
        return self.responder(path,params)


class DiagnosticTests(unittest.TestCase):
    def test_shared_identity_is_exact_not_title_search(self):
        self.assertEqual(kit.subject('https://api3.aoneroom.com/detail?id=3620534210064175096'),kit.SEED)
        with self.assertRaises(ValueError): kit.subject('Reacher S1-S4')
        with self.assertRaises(ValueError): kit.subject('https://example.test/reacher')

    def test_apk_dub_codes_and_original_flag(self):
        self.assertEqual(kit.category({'lanCode':'in_id','lanName':'Indonesia'}),'INDONESIAN')
        self.assertEqual(kit.category({'lanCode':'eng','lanName':'English dub'}),'ENGLISH')
        self.assertEqual(kit.category({'original':True,'lanName':'Korean'}),'ORIGINAL')
        for dub in ({},{'lanName':'Hindi'},{'lanName':'English','lanCode':'hi'}):
            self.assertEqual(kit.category(dub),'UNSUPPORTED')

    def test_summary_drops_credentials_signed_urls_and_unknown_fields(self):
        secret='SENSITIVE_SENTINEL'
        raw={'subjectId':'123','authorization':secret,'signCookie':secret,'password':secret,
             'detailUrl':'https://movie-box.co/movies/reacher-known?Signature='+secret,
             'title':'Reacher [Indonesian]','releaseDate':'2022-01-01',
             'items':[{'resourceId':'r1','se':1,'ep':1,'url':'https://cdn.test/movie?token='+secret,
                       'signCookie':secret,'sourceUrl':secret}],
             'streams':[{'url':secret,'signHeaderKey':secret}],
             'playUrl':{'urlType':'PLAY_URL_WEB','playUrl':secret}}
        result=kit.summary(raw)
        self.assertNotIn(secret,json.dumps(result))
        self.assertNotIn('https://',json.dumps(result))
        self.assertEqual(result['detailPath'],'reacher-known')
        self.assertEqual(result['title'],'Reacher [Indonesian]')
        self.assertEqual(result['year'],2022)
        self.assertEqual(result['resources'][0]['media_validated'],'NOT_TESTED')

    def test_apk_sparse_and_max_ep_indices_are_declarations_not_media(self):
        d={'seasons':[{'se':1,'maxEp':8,'allEp':'1,3,8'}]}
        self.assertTrue(kit.episode_declared(d,1,1))
        self.assertFalse(kit.episode_declared(d,1,2))
        self.assertFalse(kit.episode_declared(d,2,1))
        self.assertTrue(kit.episode_declared({'seasons':[{'se':1,'maxEp':8}]},1,1))
        self.assertTrue(kit.episode_declared({'seasons':[{'se':0,'maxEp':0}]},0,0))

    def test_access_denied_stops_without_new_hosts_or_other_episodes(self):
        for code in (401,403,441):
            g=FakeGuest(lambda p,q:({'http':code},{}))
            report=kit.probe(g,[(kit.SEED,1,1)])
            self.assertEqual(len(g.calls),1)
            self.assertEqual(report['requests'][0]['stop_reason'],'ACCESS_DENIED_NO_HOST_OR_REGION_WORKAROUND')

    def test_http_200_access_error_is_not_treated_as_metadata(self):
        g=FakeGuest(lambda p,q:({'http':200,'code':'403'},{}))
        r=kit.probe(g,[(kit.SEED,1,1)])
        self.assertEqual(len(g.calls),1)
        self.assertIn('stop_reason',r['requests'][0])

    def test_real_observed_denial_fixtures_stop_without_media_claim(self):
        observed=json.loads((Path(__file__).parent/'observed_metadata_denials.json').read_text())
        for item in observed['requests']:
            g=FakeGuest(lambda p,q:({'http':item['http'],'code':str(item['body']['code'])},{}))
            report=kit.probe(g,[(observed['subjectId'],1,1)])
            self.assertEqual(len(g.calls),1)
            self.assertEqual(report['device_playback'],'NOT_TESTED')
            self.assertEqual(report['media_validation'],'NOT_TESTED')

    def test_null_metadata_and_malformed_path_stay_unknown(self):
        g=FakeGuest(lambda p,q:({'http':200},{'dubs':None,'seasons':None,'items':None}))
        report=kit.probe(g,[(kit.SEED,1,1)])
        self.assertEqual(len(g.calls),3)
        self.assertEqual(kit.detail_path({'detailUrl':'https://['}),'UNKNOWN')
        self.assertEqual(kit.identity(None),'UNKNOWN')
        self.assertEqual(kit.identity(True),'UNKNOWN')
        self.assertFalse(report['relationships'])

    def test_server_dubs_only_and_exact_resource_episode(self):
        def respond(path,q):
            if path.endswith('dub-info'):
                return {'http':200},{'dubs':[{'subjectId':'456','lanCode':'in_id'},
                    {'subjectId':'900','lanName':'Hindi'},{'subjectId':'901'}]}
            if path.endswith('season-info'):
                return {'http':200},{'seasons':[{'se':1,'maxEp':8,'allEp':'1,2,4'}]}
            if path.endswith('resource'):
                return {'http':200},{'subjectId':q['subjectId'],'items':[{'resourceId':'right','se':1,'ep':q['epFrom']},
                    {'resourceId':'wrong','se':1,'ep':99}]}
            return {'http':200},{'subjectId':q['subjectId']}
        g=FakeGuest(respond);r=kit.probe(g,[(kit.SEED,1,1)])
        self.assertEqual({q['subjectId'] for _,q in g.calls},{kit.SEED,'456'})
        resources=[row for row in r['requests'] if row['endpoint']=='resource']
        self.assertEqual([(x['requested_se'],x['requested_ep']) for x in resources],[(1,1),(1,2)]*2)
        self.assertTrue(all(x['exact_episode_resources']==1 for x in resources))
        self.assertFalse(any('play-info' in p for p,_ in g.calls))

    def test_wrong_subject_resource_cannot_become_verified_identity(self):
        def respond(path,q):
            if path.endswith('season-info'): return {'http':200},{'seasons':[{'se':1,'allEp':'1'}]}
            if path.endswith('resource'): return {'http':200},{'subjectId':'999','items':[{'se':1,'ep':1}]}
            return {'http':200},{}
        r=kit.probe(FakeGuest(respond),[(kit.SEED,1,1)])
        item=r['requests'][-1]
        self.assertFalse(item['exact_subject']);self.assertEqual(item['exact_episode_resources'],'UNKNOWN')

    def test_log_parser_reconstructs_only_allowed_fields(self):
        raw='D/MovieBox: [PLAYBACK-H5] subject=123 parsed=4 audioFiltered=1 candidates=3 validated=2 se=1 ep=1 Cookie=PRIVATE URL=https://cdn.test/?Signature=PRIVATE'
        event=kit.sanitize_log(raw)
        self.assertEqual(event['validated'],2)
        self.assertNotIn('PRIVATE',json.dumps(event))
        self.assertNotIn('https:',json.dumps(event))
        self.assertIsNone(kit.sanitize_log('D/OtherApp: subject=123 Cookie=PRIVATE'))
        endpoint=kit.sanitize_log('D/MovieBox: [PLAYBACK] api3.aoneroom.com/wefeed-mobile-bff/subject-api/get HTTP=403 Bearer PRIVATE')
        self.assertEqual(endpoint['http'],403)
        self.assertNotIn('PRIVATE',json.dumps(endpoint))

    def test_pinned_protocol_source_rejects_tampered_content(self):
        class Response(io.BytesIO):
            def __enter__(self): return self
            def __exit__(self,*args): self.close()
        with patch('urllib.request.urlopen',return_value=Response(b'wrong-source')):
            with self.assertRaisesRegex(ValueError,'HASH_MISMATCH'): kit.Guest().configure()

    def test_archive_contains_only_sanitized_json_no_network_in_logs_only(self):
        with tempfile.TemporaryDirectory() as folder:
            with patch('sys.argv',['kit','--logs-only','--out',folder]), patch.object(kit.Guest,'configure',side_effect=AssertionError('network')):
                with contextlib.redirect_stdout(io.StringIO()): kit.main()
            paths=list(Path(folder).glob('*.zip'));self.assertEqual(len(paths),1)
            with zipfile.ZipFile(paths[0]) as z:
                self.assertEqual(z.namelist(),['diagnostic.json'])
                self.assertEqual(json.loads(z.read('diagnostic.json'))['selections'][0]['subjectId'],kit.SEED)


class V2DiagnosticTests(unittest.TestCase):
    def test_resource_wire_query_matches_verified_sorted_contract(self):
        params={'subjectId':'4149489342181954296','page':'1','perPage':10,'all':0,'startPosition':1,
                'endPosition':1,'pagerMode':0,'resolution':0,'se':1,'epFrom':1,'epTo':1}
        expected='all=0&endPosition=1&epFrom=1&epTo=1&page=1&pagerMode=0&perPage=10&resolution=0&se=1&startPosition=1&subjectId=4149489342181954296'
        self.assertEqual(kit.wire_query(params),expected)
        self.assertEqual(kit.wire_query(dict(reversed(list(params.items())))),expected)

    def test_v2_error_classification_does_not_export_messages(self):
        for text,hint in [('signature invalid PRIVATE https://secret.test','SIGNATURE_RELATED'),
                          ('app version old PRIVATE','APP_VERSION_RELATED'),
                          ('subscription required PRIVATE','MEMBERSHIP_RELATED'),
                          ('unrecognized PRIVATE','UNKNOWN')]:
            result=kit.error_hint({'message':text,'Cookie':'PRIVATE'})
            self.assertEqual(result,hint);self.assertNotIn('PRIVATE',result)

    def test_nested_resource_diagnostics_preserve_ids_without_urls_or_cookies(self):
        d={'detailUrl':'','resourceDetectors':[{'type':0,'resourceId':'r123','resourceLink':'https://cdn.test/a?Signature=PRIVATE',
             'downloadUrl':'https://cdn.test/d?token=PRIVATE','signCookie':'PRIVATE',
             'resolutionList':[{'resourceId':'r456','se':1,'ep':1,'resolution':720,'url':'PRIVATE'}]}],
           'streams':[{'id':'s1','url':'https://cdn.test/upgrade-notice.mp4?Signature=PRIVATE','signCookie':'PRIVATE','format':'DEFAULT'}]}
        r=kit.summary(d)
        self.assertEqual(r['resource_detectors'][0]['resolution_resources'][0]['resourceId'],'r456')
        self.assertTrue(r['streams_candidates'][0]['known_notice'])
        self.assertNotIn('PRIVATE',json.dumps(r));self.assertNotIn('https:',json.dumps(r))
        self.assertEqual(r['detail_url_shape']['state'],'EMPTY')

    def test_407_does_not_retry_another_resource_episode(self):
        def respond(p,q):
            if p.endswith('get'): return {'http':200},{'subjectId':q['subjectId']}
            if p.endswith('season-info'): return {'http':200},{'seasons':[{'se':1,'maxEp':8}]}
            if p.endswith('resource'): return {'http':407,'code':'407'},{}
            return {'http':200},{}
        g=FakeGuest(respond);r=kit.probe(g,[(kit.SEED,1,1)])
        self.assertEqual(len([p for p,q in g.calls if p.endswith('resource')]),1)
        self.assertEqual(r['requests'][-1]['stop_reason'],'RESOURCE_ACCESS_DENIED_NO_EPISODE_RETRY')

    def test_related_target_and_control_share_cached_requests(self):
        ind='4149489342181954296'
        def respond(p,q):
            if p.endswith('dub-info'): return {'http':200},{'dubs':[{'subjectId':kit.SEED,'original':True},{'subjectId':ind,'lanCode':'in_id'}]}
            if p.endswith('season-info'): return {'http':200},{'seasons':[{'se':1,'allEp':'1'}]}
            if p.endswith('get'): return {'http':200},{'subjectId':q['subjectId']}
            return {'http':200},{'subjectId':q['subjectId'],'items':[]}
        g=FakeGuest(respond);r=kit.probe(g,[(ind,1,1),(kit.SEED,1,1)])
        signatures=[(p,tuple(sorted(q.items()))) for p,q in g.calls]
        self.assertEqual(len(signatures),len(set(signatures)))
        self.assertTrue(r['control_related_to_target'])
        self.assertTrue(any(row['cache_hit'] for row in r['requests']))

    def test_native_diagnostic_is_scoped_to_proven_reacher_ids_and_exact_episode(self):
        def respond(p,q):
            if p.endswith('get'): return {'http':200},{'subjectId':q['subjectId']}
            if p.endswith('season-info'): return {'http':200},{'seasons':[{'se':1,'allEp':'1'}]}
            if p.endswith('play-info'): return {'http':200},{'subjectId':q['subjectId'],'se':1,'ep':99}
            return {'http':200},{}
        for sid in (kit.SEED,'123'):
            g=FakeGuest(respond);r=kit.probe(g,[(sid,1,1)],inspect_playback=True)
            native=[row for row in r['requests'] if row['endpoint']=='play-info']
            self.assertEqual(len(native),int(sid==kit.SEED))
            if native:self.assertEqual(native[0]['response_identity'],'MISMATCH')
            self.assertEqual(r['media_validation'],'NOT_TESTED')

    def test_real_phone_fixture_proves_identity_but_not_media(self):
        f=json.loads((Path(__file__).parent/'phone_reacher_metadata_20261016.json').read_text())
        self.assertTrue(f['bootstrap']['guest_session_available'])
        links=f['relationships']
        self.assertTrue(any(r['to']==kit.SEED and r['category']=='ORIGINAL' for r in links))
        self.assertTrue(any(r['to']=='4149489342181954296' and r['category']=='INDONESIAN' for r in links))
        resources=[r for r in f['distinct_response_records'] if r['endpoint']=='resource']
        self.assertTrue(resources and all(r['http']==407 for r in resources))
        self.assertEqual(f['media_validation'],'NOT_TESTED')

if __name__=='__main__': unittest.main()
