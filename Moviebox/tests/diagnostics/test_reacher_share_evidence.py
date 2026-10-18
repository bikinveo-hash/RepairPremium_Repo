"""Real sanitized Share/API observations; never claims plugin/device playback."""
import json
from pathlib import Path
import unittest


class ShareEvidenceTests(unittest.TestCase):
    def evidence(self):
        return json.loads((Path(__file__).parent/'reacher_share_evidence_20261016.json').read_text())

    def test_server_redirects_supply_exact_subject_paths_not_title_guesses(self):
        rows=self.evidence()['redirects']
        actual={r['subjectId']:r['detailPath'] for r in rows}
        self.assertEqual(actual,{'3620534210064175096':'reacher-e1nw56h5sj4',
                                 '4149489342181954296':'reacher-indonesian-WjrjjPIHwW4'})
        self.assertTrue(all(r['http']==301 and r['detailHost']=='movie-box.co' for r in rows))
        self.assertTrue(all(set(r)=={'shareUrl','http','detailHost','detailPath','subjectId'} for r in rows))

    def test_live_controls_prove_referer_context_not_query_path_alone(self):
        rows=self.evidence()['controlled_query_referer_requests']
        by_context={(bool(r['queryPath']),bool(r['refererPath'])):r for r in rows}
        self.assertTrue(all(r['http']==200 and r['code']==0 for r in rows))
        self.assertFalse(by_context[True,False]['hasResource'])
        self.assertEqual(by_context[True,False]['counts']['streams'],0)
        self.assertTrue(by_context[False,True]['hasResource'])
        self.assertTrue(by_context[True,True]['hasResource'])
        self.assertEqual(by_context[True,True]['counts'],{'streams':3,'dash':1,'hls':0})
        self.assertTrue(all(s['duration']==3345 for s in by_context[True,True]['media_metadata']))

    def test_real_streams_and_app_video_are_separate_from_plugin_media_validation(self):
        e=self.evidence();counts={r['subjectId']:r['counts'] for r in e['h5_exact_provider_headers']}
        self.assertEqual(counts['3620534210064175096'],{'streams':3,'dash':1,'hls':0})
        self.assertEqual(counts['4149489342181954296'],{'streams':2,'dash':1,'hls':0})
        self.assertTrue(e['video']['observedScenesAdvance'])
        self.assertEqual(e['video']['visibleDurationSeconds'],3345)
        self.assertFalse(e['video']['unmodifiedOfficialOriginVerified'])
        self.assertEqual(e['video']['pluginPlaybackTest'],'NOT_TESTED')
        checks=[c for r in e['production_validator_real_http'] for c in r.get('mediaChecks',[])]
        self.assertTrue(checks and not any(c['mediaValidated'] for c in checks))
        self.assertEqual(e['separate_dash_http']['http'],429)
        self.assertFalse(e['separate_dash_http']['mediaValidated'])

    def test_evidence_omits_credentials_signed_urls_and_referral_identity(self):
        e=self.evidence()
        def inspect(value):
            if isinstance(value,dict):
                for k,v in value.items():
                    self.assertNotIn(k.lower(),{'cookie','authorization','signcookie','x-mb-token','x-user','uid'})
                    inspect(v)
            elif isinstance(value,list):
                for v in value:inspect(v)
        inspect(e)
        text=json.dumps(e)
        for forbidden in ('Signature=','Policy=','uid=','Bearer '):self.assertNotIn(forbidden,text)

if __name__=='__main__':unittest.main()
