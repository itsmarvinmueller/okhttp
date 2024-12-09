package okhttp3.internal.deprecation

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import org.yaml.snakeyaml.Yaml

class DeprecationInterceptor(private val customDeprecationHeader: Set<String>) : Interceptor {
  private var client: OkHttpClient = OkHttpClient()

  override fun intercept(chain: Interceptor.Chain): Response {
    // Standard interceptor chain
    val request = chain.request()
    val response = chain.proceed(request)

    // Check for deprecation HTTP-Header
    val defaultDeprecationHeader: Set<String> = setOf("sunset", "deprecation")
    val lowerCaseCustomDeprecationHeader: Set<String> = customDeprecationHeader.mapTo(mutableSetOf()) { it.lowercase() }
    val deprecationHeader: Set<String> = defaultDeprecationHeader + lowerCaseCustomDeprecationHeader
    val responseHeaderNames: Set<String> = response.headers.names()
    val lowerCaseResponseHeaderNames: Set<String> = responseHeaderNames.mapTo(mutableSetOf()) { it.lowercase() }
    val headerIntersect: Set<String> = deprecationHeader.intersect(lowerCaseResponseHeaderNames)
    val deprecationHeaderFound: Boolean = headerIntersect.isNotEmpty()

    // Set default values for deprecation oas
    var areParameterDeprecated: Boolean = false
    var deprecatedParameterNames: Set<String> = emptySet()
    var isOperationDeprecated: Boolean = false

    // Advanced deprecation analysis if no deprecation header are present.
    if (!deprecationHeaderFound) {
      // Init all variables that are needed for the analysis
      val oasParser: OpenAPIParser = OpenAPIParser()
      val requestURL: HttpUrl = request.url
      val requestMethod: String = request.method

      // Build the url that is used as an entry point for searching the oas.
      // Here we use the request url but without the query and fragment.
      val requestBaseURL: HttpUrl =
        HttpUrl.Builder()
          .scheme(requestURL.scheme)
          .host(requestURL.host)
          .port(requestURL.port)
          .encodedPath(requestURL.encodedPath)
          .build()

      // Try to find the oas.
      val (openAPISpecification: JSONObject?, path: String) = findOpenAPISpecification(requestBaseURL)

      // Analyse the oas if existent, otherwise skip.
      if (openAPISpecification != null && path.startsWith("/")) {
        isOperationDeprecated = oasParser.isOperationDeprecated(openAPISpecification, path, requestMethod)

        // Check if request parameter are deprecated, if existing.
        if (requestURL.querySize > 0) {
          val requestParameter: Set<String> = requestURL.queryParameterNames
          val lowerCaseRequestParameter: Set<String> = requestParameter.mapTo(mutableSetOf()) { it.lowercase() }
          val (parameterDeprecated: Boolean, deprecatedParameter: Set<String>) =
            oasParser.areParameterDeprecated(
              openAPISpecification,
              path,
              requestMethod,
              lowerCaseRequestParameter,
            )
          areParameterDeprecated = parameterDeprecated
          deprecatedParameterNames = deprecatedParameter
        }
      }
    }

    // Create a combined boolean that indicates if anything of the call destination is deprecated.
    val deprecated: Boolean = deprecationHeaderFound || isOperationDeprecated || areParameterDeprecated
    // Add deprecation boolean and deprecated parameter to the response.
    val deprecationResponse =
      response.newBuilder()
        .deprecated(deprecated)
        .deprecatedParameter(deprecatedParameterNames)
        .build()
    return deprecationResponse
  }

  /**
   * This method retrieves the OpenAPI specification from a given URL.
   *
   * @param initialURL The initial URL from which to fetch the OpenAPI specification. It should be in the form of an `HttpUrl`.
   *
   * @return A `Pair` where:
   *         - The first element is a `JSONObject?`, which contains the OpenAPI specification if successfully retrieved, or `null` if not found.
   *         - The second element is a `String` providing the path where the OpenAPI specification was found.
   */
  private fun findOpenAPISpecification(initialURL: HttpUrl): Pair<JSONObject?, String> {
    var openAPISpecification: JSONObject? = null
    var searchOAS: Boolean = true
    var oasURL: HttpUrl = initialURL
    val endURL: HttpUrl =
      HttpUrl.Builder()
        .scheme(initialURL.scheme)
        .host(initialURL.host)
        .port(initialURL.port)
        .build()
    var path: String = ""

    while (searchOAS) {
      // Try the JSON version
      val jsonOasURL =
        oasURL.newBuilder()
          .addPathSegment("openapi.json")
          .build()
      val jsonOasResponse: Response = requestOAS(jsonOasURL)
      if (jsonOasResponse.code == 200) {
        try {
          val responseBody: String = jsonOasResponse.body.string()
          val possibleOpenAPISpecification = JSONObject(responseBody)
          if (possibleOpenAPISpecification.has("openapi") && possibleOpenAPISpecification.has("info")) {
            openAPISpecification = possibleOpenAPISpecification
            break
          }
        } catch (e: JSONException) {
          System.err.println("No JSON: $jsonOasURL")
          System.err.println("Skip. Try subpath.")
        }
      }

      // Try the YAML version
      val yamlOasURL =
        oasURL.newBuilder()
          .addPathSegment("openapi.yaml")
          .build()
      val yamlOasResponse: Response = requestOAS(yamlOasURL)
      if (yamlOasResponse.code == 200) {
        val yamlParser = Yaml()
        try {
          val parsedYaml: Map<String, Any> = yamlParser.load(yamlOasResponse.body.string())
          val possibleOpenAPISpecification = JSONObject(parsedYaml)
          if (possibleOpenAPISpecification.has("openapi") && possibleOpenAPISpecification.has("info")) {
            openAPISpecification = possibleOpenAPISpecification
            break
          }
        } catch (e: JSONException) {
          System.err.println("Error occurred in YAML OAS: $yamlOasURL")
          System.err.println("Skip. Try subpath.")
        } catch (e: ClassCastException) {
          System.err.println("Error occurred in YAML OAS: $yamlOasURL")
          System.err.println("Skip. Try subpath.")
        }
      }

      // Set new url for OAS search or end search if search url is only the url host section.
      if (oasURL != endURL) {
        val lastPathSegment = oasURL.encodedPathSegments.last()
        path = "/$lastPathSegment$path"
        oasURL =
          oasURL.newBuilder()
            .removePathSegment(oasURL.pathSize - 1)
            .build()
      } else {
        searchOAS = false
      }
    }
    return Pair(openAPISpecification, path)
  }

  private fun requestOAS(url: HttpUrl): Response {
    val request: Request =
      Request.Builder()
        .url(url)
        .build()
    return client.newCall(request).execute()
  }
}
