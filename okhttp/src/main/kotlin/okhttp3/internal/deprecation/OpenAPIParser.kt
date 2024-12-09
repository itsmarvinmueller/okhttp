package okhttp3.internal.deprecation

import org.json.JSONArray
import org.json.JSONObject

class OpenAPIParser {
  /**
   * This method checks whether an operation is deprecated, for an OpenAPI specification.
   *
   * @param oas The OpenAPI specification in JSON format.
   * @param path The path of the operation. This needs the slash at the beginning.
   * @param method The method which is used to call this operation. It is irrelevant if it is lowercase or uppercase.
   *
   * @return Returns a boolean indicating whether the operation is deprecated.
   */
  fun isOperationDeprecated(
    oas: JSONObject,
    requestPath: String,
    requestMethod: String,
  ): Boolean {
    // Check if the "Paths Object" exists, if not the OpenAPI-Specification is not valid.
    val pathsObject: JSONObject =
      oas.optJSONObject("paths")
        ?: throw RuntimeException("OpenAPI specification invalid.")
    // Check if the "requestPath" exists in the "Paths Object".
    val pathItemObject: JSONObject =
      pathsObject.optJSONObject(requestPath)
        ?: throw RuntimeException("Path $requestPath not found in the OpenAPI specification.")
    // Check if the "requestMethod" exists in the "Path Item Object".
    val operationObject: JSONObject =
      pathItemObject.optJSONObject(requestMethod.lowercase())
        ?: throw RuntimeException("Method ${requestMethod.uppercase()} for path $requestPath not found in the OpenAPI specification.")
    // Return whether the "Operation Object" is deprecated.
    return operationObject.optBoolean("deprecated", false)
  }

  /**
   * This method checks whether any parameter of an operation is deprecated, for an OpenAPI specification.
   *
   * @param oas The OpenAPI specification in JSON format.
   * @param path The path of the operation. This needs the slash at the beginning.
   * @param method The method which is used to call this operation. It is irrelevant if it is lowercase or uppercase.
   * @param parameter The parameters used to call this operation.
   *
   * @return Returns a tuple containing a boolean indicating whether parameters are deprecated and a list of parameters that are deprecated.
   */
  fun areParameterDeprecated(
    oas: JSONObject,
    requestPath: String,
    requestMethod: String,
    requestParameter: Set<String>,
  ): Pair<Boolean, Set<String>> {
    // Check if the "Paths Object" exists, if not the OpenAPI-Specification is not valid.
    val pathsObject: JSONObject =
      oas.optJSONObject("paths")
        ?: throw RuntimeException("OpenAPI specification invalid.")
    // Check if the "requestPath" exists in the "Paths Object".
    val pathItemObject: JSONObject =
      pathsObject.optJSONObject(requestPath)
        ?: throw RuntimeException("Path $requestPath not found in the OpenAPI specification.")
    // Check if the "requestMethod" exists in the "Path Item Object".
    val operationObject: JSONObject =
      pathItemObject.optJSONObject(requestMethod.lowercase())
        ?: throw RuntimeException("Method ${requestMethod.uppercase()} for path $requestPath not found in the OpenAPI specification.")
    // Check if the array of "Parameter Object" exists in the "Operation Object".
    val parameterObjectArray: JSONArray =
      operationObject.optJSONArray("parameters")
        ?: throw RuntimeException(
          "No parameters found for path $requestPath with method ${requestMethod.uppercase()} in the OpenAPI specification.",
        )

    // Empty set for the parameter that are deprecated and used in the request.
    val deprecatedParameters: MutableSet<String> = mutableSetOf()
    // Loop over all "Parameter Object" in the array, check whether they are deprecated and add the parameter name to the set if deprecated.
    // Additionally, we only look at the parameter that are present in the query, because our "requestParameter" are extracted from the query.
    for (i in 0 until parameterObjectArray.length()) {
      val parameterObject: JSONObject = parameterObjectArray.optJSONObject(i)
      if (parameterObject.optBoolean(
          "deprecated",
          false,
        ) && requestParameter.contains(parameterObject.optString("name")) && parameterObject.getString("in") == "query"
      ) {
        deprecatedParameters.add(parameterObject.optString("name"))
      }
    }
    // Return a pair of a boolean that indicates if "requestParameter" are deprecated and a set of those.
    return Pair(deprecatedParameters.isNotEmpty(), deprecatedParameters)
  }
}
