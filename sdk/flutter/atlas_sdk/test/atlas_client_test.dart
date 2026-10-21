import 'dart:convert';

import 'package:atlas_sdk/atlas_sdk.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:test/test.dart';

void main() {
  group('AtlasClient.complete', () {
    test('returns the text from a successful Anthropic-shaped response', () async {
      late http.Request captured;
      final client = AtlasClient(
        appKey: 'app-key',
        anonKey: 'anon-key',
        baseUrl: 'https://example.test/atlas-sdk',
        httpClient: MockClient((request) async {
          captured = request;
          return http.Response(
            jsonEncode({
              'content': [
                {'type': 'text', 'text': 'Legenda gerada'},
              ],
            }),
            200,
            headers: {'content-type': 'application/json'},
          );
        }),
      );

      final text = await client.complete(
        endUserId: 'user-1',
        systemPrompt: 'Você é um assistente.',
        userPrompt: 'Sugira uma legenda.',
      );

      expect(text, 'Legenda gerada');
      expect(captured.url.toString(), 'https://example.test/atlas-sdk/anthropic');
      expect(captured.headers['X-Atlas-App-Key'], 'app-key');
      expect(captured.headers['X-Atlas-End-User'], 'user-1');
      expect(captured.headers['apikey'], 'anon-key');
      final body = jsonDecode(captured.body) as Map<String, dynamic>;
      expect(body['system'], 'Você é um assistente.');
      expect(body['messages'], [{'role': 'user', 'content': 'Sugira uma legenda.'}]);
    });

    test('throws AtlasUnauthorizedException on a 401', () async {
      final client = AtlasClient(
        appKey: 'wrong-key',
        anonKey: 'anon-key',
        httpClient: MockClient((request) async => http.Response('{}', 401)),
      );

      expect(
        () => client.complete(endUserId: 'user-1', systemPrompt: 's', userPrompt: 'u'),
        throwsA(isA<AtlasUnauthorizedException>()),
      );
    });

    test('throws AtlasQuotaExceededException on a 429', () async {
      final client = AtlasClient(
        appKey: 'app-key',
        anonKey: 'anon-key',
        httpClient: MockClient((request) async => http.Response('{}', 429)),
      );

      expect(
        () => client.complete(endUserId: 'user-1', systemPrompt: 's', userPrompt: 'u'),
        throwsA(isA<AtlasQuotaExceededException>()),
      );
    });

    test('throws AtlasProviderException on an unexpected response shape', () async {
      final client = AtlasClient(
        appKey: 'app-key',
        anonKey: 'anon-key',
        httpClient: MockClient((request) async => http.Response(jsonEncode({'content': []}), 200)),
      );

      expect(
        () => client.complete(endUserId: 'user-1', systemPrompt: 's', userPrompt: 'u'),
        throwsA(isA<AtlasProviderException>()),
      );
    });

    test('throws AtlasProviderException on a 500', () async {
      final client = AtlasClient(
        appKey: 'app-key',
        anonKey: 'anon-key',
        httpClient: MockClient((request) async => http.Response('oops', 500)),
      );

      expect(
        () => client.complete(endUserId: 'user-1', systemPrompt: 's', userPrompt: 'u'),
        throwsA(isA<AtlasProviderException>()),
      );
    });
  });
}
