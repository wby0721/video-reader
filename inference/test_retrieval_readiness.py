"""Readiness lifecycle UNIT tests; fake weights, not model quality measurements."""
import asyncio
import importlib.util
import os
from pathlib import Path
import unittest
from unittest.mock import Mock

os.environ['WATCH_BACKEND'] = '0'

def load(name):
    spec = importlib.util.spec_from_file_location('test_' + name, Path(__file__).parent / name / 'app.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

class ReadinessTest(unittest.TestCase):
    def test_health_requires_successful_warmup(self):
        for name in ('embedding', 'reranker'):
            with self.subTest(name=name):
                module = load(name)
                module._model = Mock()
                self.assertEqual(module.health().status_code, 503)
                async def run():
                    async with module.lifespan(module.app):
                        self.assertEqual(module.health().status_code, 200)
                    self.assertEqual(module.health().status_code, 503)
                asyncio.run(run())
                method = module._model.encode if name == 'embedding' else module._model.predict
                method.assert_called_once()

    def test_failed_model_never_becomes_ready(self):
        for name in ('embedding', 'reranker'):
            with self.subTest(name=name):
                module = load(name)
                module._warmup = Mock(side_effect=RuntimeError('model unavailable'))
                async def run():
                    async with module.lifespan(module.app):
                        self.fail('must not start')
                with self.assertRaises(RuntimeError):
                    asyncio.run(run())
                self.assertEqual(module.health().status_code, 503)

if __name__ == '__main__':
    unittest.main()
