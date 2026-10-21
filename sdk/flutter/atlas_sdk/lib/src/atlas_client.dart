import 'dart:convert';

import 'package:http/http.dart' as http;

import 'atlas_exceptions.dart';

/// Default base URL for Atlas's partner-app gateway (the `atlas-sdk` Supabase Edge Function).
/// Override via the constructor if a partner app ever needs to point at a different environment.
const String defaultAtlasSdkBaseUrl = 'https://hqfezdzvsmuakheldtcr.functions.supabase.co/atlas-sdk';

/// A thin client for Atlas's partner-app AI gateway — the same idea as `atlas-ai` (Atlas's own
/// Android app's gateway) but authenticated by a per-app key instead of an Atlas account, since a
/// partner app (Unyx today, maybe others later) has its own, unrelated user base. See
/// `supabase/functions/atlas-sdk/index.ts` in the Atlas repo for the server side of this contract.
///
/// [endUserId] is deliberately NOT part of this client's own state — every call takes it as a
/// parameter, so a long-lived [AtlasClient] instance never ends up tagging a new user's request
/// with a previous user's id after a logout/login inside the host app.
class AtlasClient {
  AtlasClient({
    required String appKey,
    required String anonKey,
    String baseUrl = defaultAtlasSdkBaseUrl,
    http.Client? httpClient,
    Duration timeout = const Duration(seconds: 30),
  })  : _appKey = appKey,
        _anonKey = anonKey,
        _baseUrl = baseUrl,
        _timeout = timeout,
        _http = httpClient ?? http.Client();

  final String _appKey;
  final String _anonKey;
  final String _baseUrl;
  final Duration _timeout;
  final http.Client _http;

  /// Generates text from a single system+user prompt — covers every real use this SDK's first
  /// partner app has today (caption suggestions, hashtag suggestions, content-moderation
  /// analysis are all "complete this prompt, read back text"). For anything needing a real
  /// multi-turn conversation, use [sendMessages] directly instead.
  Future<String> complete({
    required String endUserId,
    required String systemPrompt,
    required String userPrompt,
    int maxTokens = 1024,
  }) async {
    final data = await sendMessages(
      endUserId: endUserId,
      system: systemPrompt,
      messages: [
        {'role': 'user', 'content': userPrompt},
      ],
      maxTokens: maxTokens,
    );
    final content = data['content'];
    if (content is List && content.isNotEmpty) {
      final first = content.first;
      if (first is Map && first['text'] is String) return first['text'] as String;
    }
    throw const AtlasProviderException('resposta em formato inesperado');
  }

  /// Lower-level call matching the Anthropic Messages API shape (`system`/`messages`/
  /// `max_tokens`) — the gateway relays it to Claude almost verbatim (see `invokeAnthropic` in
  /// the Atlas repo's `_shared/ai/providers.ts`), so the response is the raw Anthropic response
  /// body too. Prefer [complete] unless a caller genuinely needs multi-turn history.
  Future<Map<String, dynamic>> sendMessages({
    required String endUserId,
    String? system,
    required List<Map<String, dynamic>> messages,
    int maxTokens = 1024,
  }) async {
    final http.Response response;
    try {
      response = await _http
          .post(
            Uri.parse('$_baseUrl/anthropic'),
            headers: {
              'Content-Type': 'application/json',
              'apikey': _anonKey,
              'X-Atlas-App-Key': _appKey,
              'X-Atlas-End-User': endUserId,
            },
            body: jsonEncode({
              if (system != null) 'system': system,
              'messages': messages,
              'max_tokens': maxTokens,
            }),
          )
          .timeout(_timeout);
    } on Exception catch (error) {
      throw AtlasProviderException('falha de rede: $error');
    }

    switch (response.statusCode) {
      case 200:
        final decoded = jsonDecode(response.body);
        if (decoded is Map<String, dynamic>) return decoded;
        throw const AtlasProviderException('resposta não é um objeto JSON');
      case 401:
        throw const AtlasUnauthorizedException();
      case 429:
        throw const AtlasQuotaExceededException();
      default:
        throw AtlasProviderException('HTTP ${response.statusCode}');
    }
  }

  /// Releases the underlying HTTP client — call when this instance is no longer needed, same
  /// convention as the rest of this SDK's host apps already follow for their own services.
  void dispose() => _http.close();
}
