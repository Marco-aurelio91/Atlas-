/// Base for everything [AtlasClient] throws — lets a caller catch broadly with `on AtlasException`
/// or narrowly for one specific case.
sealed class AtlasException implements Exception {
  const AtlasException(this.message);

  final String message;

  @override
  String toString() => 'AtlasException: $message';
}

/// The app key was missing, unknown, or deactivated — a configuration problem, not something a
/// retry fixes. Kept distinct from [AtlasQuotaExceededException] in this SDK's own types even
/// though the gateway deliberately returns the same HTTP status for both (see atlas-sdk's own
/// doc on why the wire protocol doesn't let a caller tell them apart) — if that ever changes
/// server-side, callers here won't need to change how they catch these.
final class AtlasUnauthorizedException extends AtlasException {
  const AtlasUnauthorizedException() : super('Chave do Atlas SDK ausente ou inválida');
}

/// This app (or this end user, within the app) hit its request limit for the current
/// day/minute window. Expected to happen occasionally under normal use — callers should show a
/// friendly "tente novamente em um instante", not treat it as a bug.
final class AtlasQuotaExceededException extends AtlasException {
  const AtlasQuotaExceededException() : super('Limite de uso do Atlas SDK atingido — tente novamente em breve');
}

/// The gateway reached the upstream AI provider but got back something unusable (non-2xx, or a
/// 2xx body that doesn't have the shape this client expects).
final class AtlasProviderException extends AtlasException {
  const AtlasProviderException(String detail) : super('Erro do provedor de IA do Atlas: $detail');
}
