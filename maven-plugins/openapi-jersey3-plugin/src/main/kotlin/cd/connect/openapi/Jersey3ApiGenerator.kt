package cd.connect.openapi

import com.google.common.base.CaseFormat
import com.google.googlejavaformat.java.Formatter
import com.google.googlejavaformat.java.JavaFormatterOptions
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.Paths
import io.swagger.v3.oas.models.parameters.Parameter
import org.apache.commons.io.FilenameUtils
import org.apache.commons.io.IOUtils
import org.openapitools.codegen.*
import org.openapitools.codegen.languages.AbstractJavaJAXRSServerCodegen
import org.openapitools.codegen.model.ModelMap
import org.openapitools.codegen.model.ModelsMap
import org.openapitools.codegen.model.OperationsMap
import org.openapitools.codegen.utils.CamelizeOption
import org.openapitools.codegen.utils.StringUtils
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.util.*
import java.util.stream.Collectors

class Jersey3ApiGenerator : AbstractJavaJAXRSServerCodegen(), CodegenConfig {
	override fun getName(): String {
		return LIBRARY_NAME
	}

	override fun getHelp(): String {
		return "jersey3 api generator. generates all classes and interfaces with jax-rs annotations with jersey2 " +
			"extensions as necessary"
	}

	// we need to keep this for determining if we are using auth later
	private var parentPathParamCount = 0
	override fun preprocessOpenAPI(openAPI: OpenAPI) {
		super.preprocessOpenAPI(openAPI)
		if (openAPI.servers != null && openAPI.servers.size == 1) {
			additionalProperties[SERVICE_DEFAULT_URL] = openAPI.servers[0].url
		}
		if (!additionalProperties.containsKey("jersey2")) {
			additionalProperties["jersey3"] = "true"
		}
		if (additionalProperties.containsKey(PREFIX_ALL_PATHS_USING_GET)) {
			var getPath = additionalProperties[PREFIX_ALL_PATHS_USING_GET].toString()
			val parentPath = openAPI.paths[getPath]
			if (parentPath == null || parentPath.get == null) {
				throw RuntimeException(
					String.format(
						"Attempt to get path `%s` failed because it is not in spec or has no GET",
						getPath
					)
				)
			}
			if (!getPath.endsWith("/")) {
				getPath = "$getPath/"
			}

			// this allows us to pick up later for each parameter and drop them from the list if we are using
			//
			parentPath.get.parameters.forEach { p: Parameter ->
				p.addExtension(
					"x-$PREFIX_ALL_PATHS_USING_GET",
					"true"
				)
			}
			parentPathParamCount = parentPath.get.parameters.size
			val prefixPath = getPath
			val newPaths = Paths()
			openAPI.paths.forEach { path: String, pathItem: PathItem ->
				var localPath = path
				if (pathItem !== parentPath) {
					parentPath.get.parameters.forEach { parametersItem: Parameter? ->
						pathItem.addParametersItem(
							parametersItem
						)
					}
					if (localPath.startsWith("/")) {
						localPath = localPath.substring(1)
					}
					localPath = prefixPath + localPath
					newPaths[localPath] = pathItem
				}
			}
			openAPI.paths.clear()
			openAPI.paths = newPaths
		}
	}

	// stoplight has a tendency to insert rubbish in the oas.json file
	override fun postProcessModelProperty(model: CodegenModel, property: CodegenProperty) {
		super.postProcessModelProperty(model, property)
		if ("null" == property.example) {
			property.example = null
		}
		if ("uuid" == property.getDataFormat()) {
			property.isFreeFormObject = false
		}
		model.imports.remove("ApiModelProperty")
		model.imports.remove("ApiModel")
	}

	/**
	 * This gets called once we have been passed the configuration read in by the plugin.
	 */
	override fun processOpts() {
		super.processOpts()
		if (!useBeanValidation) {
			additionalProperties.remove("useBeanValidation")
		}
		apiTemplateFiles.remove("api.mustache")

		// no documentation, we're british
		modelDocTemplateFiles.clear()
		apiDocTemplateFiles.clear()
		modelTemplateFiles["model.mustache"] = ".java"
		if (additionalProperties.containsKey("client")) {
			apiTemplateFiles["Impl.mustache"] = ".java"
			apiTemplateFiles["ClientService.mustache"] = ".java"
		}
		if (additionalProperties.containsKey("server")) {
			apiTemplateFiles["Service.mustache"] = ".java"
		}
		if (additionalProperties.containsKey("server-security")) {
			apiTemplateFiles["SecurityService.mustache"] = ".java"
		}
		if (additionalProperties.containsKey("server-delegate")) {
			apiTemplateFiles["DelegateServerService.mustache"] = ".java"
			apiTemplateFiles["DelegateService.mustache"] = ".java"
			apiTemplateFiles["SecurityService.mustache"] = ".java"
		}
		if (usingDelegateHolderPackage()) {
			apiTemplateFiles["DelegateServerService.mustache"] = ".java"
			apiTemplateFiles["SecurityService.mustache"] = ".java"
		}

//    apiTemplateFiles.put("Configuration.mustache", ".java");

		// this is the name of the library and the date package we use
		apiTestTemplateFiles.clear()
		if (additionalProperties[SERVICE_NAME] != null) {
			val serviceName = additionalProperties[SERVICE_NAME].toString()
			if (additionalProperties[SERVICE_ADDRESS] != null) {
				addJersey2Client(serviceName, additionalProperties[SERVICE_ADDRESS].toString())
			} else if (additionalProperties[SERVICE_PORT] != null) {
				addJersey2Client(
					serviceName, String.format(
						"%s-service:%s", serviceName,
						additionalProperties[SERVICE_PORT].toString()
					)
				)
			}
		}
		if (additionalProperties.containsKey(CodegenConstants.IMPL_FOLDER)) {
			implFolder = additionalProperties[CodegenConstants.IMPL_FOLDER] as String?
		}
	}

	private fun disableCamelToSnakeCaseEnumNames(): Boolean {
		additionalProperties[ENUM_CAMEL_TO_SNAKE]?.let { it ->
			val opt = it.toString().lowercase()
			return opt == "off" || opt == "false"
		}

		return false
	}

	// if we are using delegate style but are actually delegating to another implementation
	private fun usingDelegateHolderPackage(): Boolean {
		return additionalProperties.containsKey("delegateHolderPackage")
	}

	// are we using a single path: get: as a prefix path to override other methods?
	private fun usingPrefixPathSupport(): Boolean {
		return additionalProperties.containsKey(PREFIX_ALL_PATHS_USING_GET)
	}

	// ensure we remove the
	private fun stripPrefixPathSupportForDelegates(): Boolean {
		return additionalProperties.containsKey("delegatePackageStripPrefix")
	}

	override fun postProcessOperationsWithModels(objs: OperationsMap, allModels: List<ModelMap>): OperationsMap {
		super.postProcessOperationsWithModels(objs, allModels)
		val codegenOperations = getCodegenOperations(objs)

		// check if the imports of the APIs have classes that have changed their package names
		// and if so, replace them
		val imports = objs.imports
		for (anImport in imports) {
			if (anImport.containsKey("import") && anImport.containsKey("classname")) {
				val clazzName = anImport["classname"]
				val codegenModel = packageOverrideModelNames[clazzName]
				if (codegenModel != null) {
					anImport["import"] = codegenModel.getVendorExtensions()["x-package"].toString() +
						"." + codegenModel.getClassname()
				}
			}
		}

		// we need to be able to prevent voracious common-pathing if APIs are scattered because Jersey
		// can't find URLs that have the same common path offset with the @Path annotation at the top of
		// the file
		val baseUrlOverride = Optional.ofNullable(additionalProperties[SERVICE_BASE]).map { obj: Any -> obj.toString() }
			.orElse(null)
		if (baseUrlOverride != null && objs.containsKey("commonPath") && objs["commonPath"].toString()
				.startsWith(baseUrlOverride)
		) {
			val commonPath = objs["commonPath"].toString().substring(baseUrlOverride.length)
			codegenOperations!!.forEach { co: CodegenOperation -> co.path = commonPath + co.path }
			objs["commonPath"] = baseUrlOverride
		}
		if (codegenOperations!!.isNotEmpty()) {
			objs["apiName"] = codegenOperations[0].baseName
		}
		if (additionalProperties.containsKey(SERVICE_DEFAULT_URL)) {
			objs[SERVICE_DEFAULT_URL] = additionalProperties[SERVICE_DEFAULT_URL]
		}
		val className = objs.operations.classname
		for (op in codegenOperations) {
			// need to ensure the path if it has params as <> that it uses {} instead
			op.path = op.path.replace('<', '{').replace('>', '}')

			// an Object is actually a Response header. Ideally we don't ever want to return these but occasionally they
			// are required.
			if ("Object" == op.returnBaseType) {
				op.returnBaseType = "Response"
			}
			if ("void" == op.returnType) {
				op.vendorExtensions["x-void-return"] = java.lang.Boolean.TRUE
			}
			if (op.produces != null) {
				var lastProducesHasNext = op.produces.size
				for (produce in op.produces) {
					lastProducesHasNext--
					if (lastProducesHasNext > 0) {
						produce["hasMore"] = "true"
					}
				}
			}
			if (op.hasQueryParams) {
				val optionalQueryParams = op.allParams.stream().filter { p: CodegenParameter -> p.isQueryParam && !p.required }
					.collect(Collectors.toList())
				if (optionalQueryParams.size > 0) {
					op.vendorExtensions["x-has-delegator-holder"] = java.lang.Boolean.TRUE
					op.vendorExtensions["x-delegator-holder-params"] = optionalQueryParams
					val delegateHolderPrefix =
						if (additionalProperties.containsKey("delegateHolderPackage")) additionalProperties["delegateHolderPackage"].toString() + String.format(
							".%sDelegate.",
							className
						) else ""
					// additionalProperties["delegateHolderPackage"].toString() + ".${className}Delegate." + StringUtils
					// .camelize(op.operationId + "-holder", false)
					op.vendorExtensions["x-class-delegator-holder"] =
						delegateHolderPrefix + StringUtils.camelize(op.operationId + "-holder", CamelizeOption.UPPERCASE_FIRST_CHAR)
				}
			}

			// .filter { p: CodegenParameter -> !"alSlice".equals(p.paramName) && !"alOrganisation".equals(p.paramName) }
			val amStrippingPrefixPath = stripPrefixPathSupportForDelegates()
			// regardless we don't want un-required query params in this one
			val params = op.allParams.stream()
				.filter { p: CodegenParameter -> !p.isQueryParam || p.required }
				.filter { p: CodegenParameter ->
					!amStrippingPrefixPath || p.vendorExtensions == null || !p.vendorExtensions.containsKey(
						"x-$PREFIX_ALL_PATHS_USING_GET"
					)
				}
				.map { p: CodegenParameter -> p.paramName }
				.collect(Collectors.joining(","))
			if (params.isNotEmpty()) {
				op.vendorExtensions["x-has-java-params"] = java.lang.Boolean.TRUE
				op.vendorExtensions["x-java-params"] = params
				op.vendorExtensions["x-java-params-plus-types"] =
					op.allParams.stream().filter { p: CodegenParameter -> !p.isQueryParam || p.required }
						.map { p: CodegenParameter -> if (!p.isNullable) "@org.jetbrains.annotations.NotNull ${p.dataType} ${p.paramName}" else "@org.jetbrains.annotations.Nullable ${p.dataType} ${p.paramName}" }.collect(Collectors.joining(","))
			}

			// figuring out if we need a comma in the delegate is too complicated in mustache, so we figure it out here.
			if (op.allParams != null && (op.allParams.toTypedArray().size != parentPathParamCount && usingPrefixPathSupport() || !usingPrefixPathSupport() && op.allParams.size > 0) && op.authMethods != null && op.authMethods.size > 0) {
				op.vendorExtensions["x-has-auth"] = ", "
			}
			op.responses.stream().filter { r: CodegenResponse ->
				r.is2xx && !"200".equals(
					r.code,
					ignoreCase = true
				) && op.returnType != null && op.returnType == r.dataType
			}
				.findFirst()
				.ifPresent { resp: CodegenResponse -> op.vendorExtensions["statusCode"] = resp.code }
			if (op.responses.stream().noneMatch { r: CodegenResponse -> r.is2xx }) {
				op.vendorExtensions.remove("x-java-is-response-void")
				op.returnType = null // force it to be Response object
			}
			if ("void" == op.returnType && !op.vendorExtensions.containsKey("statusCode")) {
				// if this is returning void, it will in fact return a 204, so lets find the first 2xx code and tag this method
				op.responses.stream()
					.filter { r: CodegenResponse -> r.is2xx && "void" == r.dataType }
					.findFirst()
					.ifPresent { resp: CodegenResponse -> op.vendorExtensions["statusCode"] = resp.code }
			}
		}
		return objs
	}

	private var modelNames: MutableMap<String, CodegenModel> = HashMap()
	private var packageOverrideModelNames: MutableMap<String?, CodegenModel> = HashMap()
	private var newImportsBecausePackageOverrides: MutableSet<String> = HashSet()

	init {
		library = LIBRARY_NAME
		dateLibrary = "java8"
		supportedLibraries.clear()
		supportedLibraries[LIBRARY_NAME] = LIBRARY_NAME

		// tell the model about extra mustache files to generate

		// if we are using Kubernetes, we should get a service url. We separate these because the serviceName
		// is used for the Spring configuration class
		// this should appear in your config as:
		// <configOptions>
		//   <serviceName>...</serviceName> etc
		// </configOptions>
		cliOptions.add(CliOption(SERVICE_NAME, "Name of service to use for @enable"))
		cliOptions.add(CliOption(SERVICE_ADDRESS, "Name of service to use for @enable"))
		cliOptions.add(CliOption(SERVICE_PORT, "Port of service to use for @enable"))
		cliOptions.add(CliOption("suppressIgnoreUnknown", "Don't add the ignore unknown to the generated models"))
		if (typeMapping == null) {
			typeMapping = HashMap()
		}
		typeMapping["void"] = "Void"

		// override the location
		templateDir = JERSEY2_TEMPLATE_FOLDER
		embeddedTemplateDir = templateDir
	}

	override fun modelFilename(templateName: String, modelName: String): String {
		if (packageOverrideModelNames.containsKey(modelName)) {
			val model = packageOverrideModelNames[modelName]
			val modelFolder = (outputFolder + File.separator + sourceFolder + File.separator +
				model!!.getVendorExtensions()["x-package"].toString().replace('.', File.separatorChar))
				.replace('/', File.separatorChar)
			val suffix = modelTemplateFiles()[templateName]
			return modelFolder + File.separator + toModelFilename(modelName) + suffix
		}
		return super.modelFilename(templateName, modelName)
	}

	override fun postProcessAllModels(originalAllModels: Map<String, ModelsMap>): Map<String, ModelsMap> {
		val allModels = super.postProcessAllModels(originalAllModels)
		allModels.keys.forEach { modelCollectionName: String ->
			val info = allModels[modelCollectionName]
			newImportsBecausePackageOverrides.forEach { pkg: String ->
				if (pkg != info!!["package"]) {
					val newImports: MutableMap<String, String> = HashMap()
					newImports["import"] = "$pkg.*"
					val imports = info.imports
					imports.add(newImports)
				}
			}
			val extraImports = checkForMapKeyOverride(modelCollectionName, allModels)
			// now walk through all the imports and re-write them
			val importStatements = info!!.imports
			val prefix = modelPackage()
			importStatements.forEach { statement: MutableMap<String?, String?> ->
				val iStatement = statement["import"]
				if (iStatement != null && iStatement.startsWith(prefix)) {
					val statementModelName = iStatement.substring(prefix.length + 1)
					if (packageOverrideModelNames.containsKey(statementModelName)) {
						val newImport =
							packageOverrideModelNames[statementModelName]!!.getVendorExtensions()["x-package"].toString() + "." + statementModelName
						extraImports.remove(newImport) // no dupes - remove new package import
						statement["import"] = newImport
					} else {
						extraImports.remove(iStatement) // no dupes
					}
				} else if (iStatement != null) {
					extraImports.remove(iStatement) // no dupes
				}
			}
			extraImports.forEach { i: String? ->
				val importMap: MutableMap<String?, String?> = HashMap()
				importMap["import"] = i
				importStatements.add(importMap)
			}
			info.models?.forEach { model ->
				val implements = model.model.vendorExtensions["x-implements"]
				if (implements != null) {
					model.model.vendorExtensions["x-implements"] =
						implements.toString().split(",").map { it.trim() }.filter { it.length > 0 }
							.joinToString(",") { "${it}<${model.model.classname}>" }
				}
			}
		}
		return allModels
	}

	private class XPropertyRef(var model: CodegenModel, var importPath: String)

	private fun checkForMapKeyOverride(modelName: String, modelMap: Map<String, ModelsMap>): MutableSet<String> {
		val extraImports: MutableSet<String> = HashSet()
		val info = modelMap[modelName]
		val models = info!!.models
		if (models.size == 1) {
			val model = models[0]["model"] as CodegenModel?
			if (model != null) {
				model.allVars.forEach { p: CodegenProperty -> resetMapOverrideKey(modelMap, extraImports, p) }
				model.vars.forEach { p: CodegenProperty -> resetMapOverrideKey(modelMap, extraImports, p) }
			}
		}
		return extraImports
	}

	private fun resetMapOverrideKey(
		modelMaps: Map<String, ModelsMap>,
		extraImports: MutableSet<String>,
		p: CodegenProperty
	) {
		if (p.isMap) {
			var keyType = "String"
			if (!p.getVendorExtensions().containsKey("x-property-ref")) {
				p.getVendorExtensions()["x-property-ref"] = keyType
			} else {
				val ref = p.getVendorExtensions()["x-property-ref"].toString()
				val refName =
					if (ref.startsWith("#/components")) extractModelFromRef(modelMaps, ref) else extractModelFromShortName(
						modelMaps,
						ref
					)
				if (refName != null) {
					extraImports.add(refName.importPath)
					keyType = refName.model.classname
					p.getVendorExtensions()["x-property-ref"] = keyType
					p.dataType = p.dataType.replace("<String,", "<$keyType,")
					p.datatypeWithEnum = p.datatypeWithEnum.replace("<String,", "<$keyType,")
				}
			}
		}
	}

	private fun extractModelFromShortName(info: Map<String, ModelsMap>, ref: String): XPropertyRef? {
		val modelInfo = info[ref]
		if (modelInfo != null) {
			val models = modelInfo.models
			if (models != null && models.size == 1) {
				val model = models[0]["model"] as CodegenModel?
				val importPath = models[0]["importPath"] as String?
				if (importPath != null && model != null) {
					return XPropertyRef(model, importPath)
				}
			}
		}
		return null
	}

	/**
	 * here we have to cut off the stuff and then return the model from the short name
	 */
	private fun extractModelFromRef(info: Map<String, ModelsMap>, ref: String): XPropertyRef? {
		val shortName = ref.substring(ref.lastIndexOf("/") + 1)
		return extractModelFromShortName(info, shortName)
	}

	override fun postProcessModels(originalModels: ModelsMap): ModelsMap {
		val newModels = super.postProcessModels(originalModels)
		val models = newModels.models
		val imports = newModels.imports
		imports.forEach { map: MutableMap<String?, String> ->
			if (map.containsKey("import")) {
				if (map["import"]!!.startsWith("io.swagger")) {
					map.remove("import")
				}
			}
		}
		imports.removeIf { obj: Map<String?, String> -> obj.isEmpty() }
		models.forEach { model: ModelMap ->
			val m = model.model
			modelNames[m.classname] = m
			if (m.getVendorExtensions() != null && m.getVendorExtensions().containsKey("x-package")) {
				packageOverrideModelNames[m.classname] = m
				val packageName = m.getVendorExtensions()["x-package"].toString()
				newModels["package"] = packageName
				model["importPath"] = packageName
				newImportsBecausePackageOverrides.add(packageName)
			} else {
				newModels["package"] = modelPackage()
			}
			m.vars.forEach { p: CodegenProperty ->
				// ensure lists have a default value of an empty array
				if (p.isArray && p.defaultValue == null) {
					if (p.uniqueItems) {
						p.defaultValue = "new java.util.HashSet<>()"
					} else {
						p.defaultValue = "new java.util.LinkedList<>()"
					}
				}
				// this overrides the name of the json value
				p.getVendorExtensions()["x-basename"]?.let {
					p.setBaseName(it.toString())
				}

				p.name?.let { name ->
					p.nameInCamelCase = StringUtils.camelize(name, CamelizeOption.UPPERCASE_FIRST_CHAR)
					p.nameInSnakeCase = CaseFormat.UPPER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, p.nameInCamelCase)
				}
				// this overrides the name of the field name
				p.getVendorExtensions()["x-longname"]?.let {
					val name = it.toString()
					p.setName(name)
					p.getter = toGetter(name)
					p.setter = toSetter(name)
					p.nameInCamelCase = StringUtils.camelize(name, CamelizeOption.UPPERCASE_FIRST_CHAR)
					p.nameInSnakeCase = CaseFormat.UPPER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, p.nameInCamelCase)
				}
			}
//			if (m.allowableValues != null && m.allowableValues["enumVars"] != null && disableCamelToSnakeCaseEnumNames() ) {
//				val enumVars = enumVars(m)
//				enumVars.forEach { value ->
//					if (value["value"])
//					value["name"] = value["name"].toString().uppercase()
//				}
//			}

			// there is a bug in 5.2.1 where we are getting duplicate enums
			if (m.allowableValues != null && m.allowableValues["enumVars"] != null && m.vendorExtensions.containsKey("x-enum-longname") ) {
				// each item in enumVars looks like a map(name: X, value: Y, isString: true/false)
				val enumVars = enumVars(m)
				val longNames = m.vendorExtensions["x-enum-longname"] as Map<String, String>
				enumVars.forEach { value: MutableMap<String, Any> ->
					var name = value["name"].toString()
					if (value["isString"] == true) {
						name = name.lowercase()
					}
					longNames[name]?.let { replacementName ->
						value["name"] = replacementName.uppercase()
					}
				}
			}
		}
		return newModels
	}

	fun enumVars(m: CodegenModel) : List<MutableMap<String,Any>> {
		return m.allowableValues["enumVars"] as List<MutableMap<String, Any>>
	}

	override fun toEnumVarName(value: String?, datatype: String?): String? {
		if (disableCamelToSnakeCaseEnumNames()) {
			if (value.isNullOrEmpty()) {
            	return "EMPTY";
        	} else {
				val name = value.uppercase()

            	return if (this.reservedWords.contains(name)) this.escapeReservedWord(name) else name;
        	}
		}
		return super.toEnumVarName(value, datatype)
	}

	override fun postProcessEnumVars(enumVars: List<Map<String?, Any?>?>?) {
		super.postProcessEnumVars(enumVars)
	}

	override fun postProcessFile(file: File, fileType: String) {
		if ("java" == FilenameUtils.getExtension(file.toString()) &&
			"off" != additionalProperties["google-format"]
		) {
			try {
				val ifile = FileReader(file)
				val inputFile = IOUtils.toString(ifile)
				ifile.close()
				val result = Formatter(
					JavaFormatterOptions.builder()
						.style(JavaFormatterOptions.Style.GOOGLE)
						.build()
				).formatSourceAndFixImports(inputFile)
				if (result.trim { it <= ' ' }.isEmpty()) {
					log.error("Unable to format `{}`", file.absolutePath)
				} else {
					val ofile = FileWriter(file)
					IOUtils.write(result, ofile)
					ofile.flush()
					ofile.close()
				}
			} catch (e: Exception) {
				log.error(
					"Failed to format file `{}` - if using JDK16 include a .mvn/jvm.config  like this project  or turn " +
						" off post processing", file.absolutePath, e
				)
			}
		}
	}

	private fun getCodegenOperations(objs: Map<String, Any>): List<CodegenOperation>? {
		return getOperations(objs)!!["operation"] as List<CodegenOperation>?
	}

	private fun getOperations(objs: Map<String, Any>): Map<String, Any>? {
		return objs["operations"] as Map<String, Any>?
	}

	override fun toModelName(name: String?): String {
		return if (name != null) super.toModelName(name) else "<<unknown-to-model-name-is-null>>"
	}

	override fun addOperationToGroup(
		tag: String, resourcePath: String, operation: Operation, co: CodegenOperation,
		operations: MutableMap<String, MutableList<CodegenOperation>>
	) {
		operations.computeIfAbsent(tag) { ArrayList() }
			.add(co)
	}

	private fun addJersey2Client(serviceName: String, serviceAddress: String) {
		System.out.printf("Service %s - located at `%s`\n", serviceName, serviceAddress)

		// standard Spring style naming
		val className = "Enable" + StringUtils.camelize(serviceName, CamelizeOption.LOWERCASE_FIRST_CHAR) + "Service"
		additionalProperties[SERVICE_NAME] = className
		additionalProperties[SERVICE_ADDRESS] = serviceAddress
		additionalProperties["package"] = modelPackage()
		supportingFiles.add(
			SupportingFile(
				"enable.mustache",
				sourceFolder + "/" + apiPackage().replace('.', '/'), "$className.java"
			)
		)
	}

	override fun apiFilename(templateName: String, tag: String): String {
		val suffix = apiTemplateFiles()[templateName]
		var result = apiFileFolder() + '/' + toApiFilename(tag) + suffix
		val ix: Int
		if (templateName.endsWith("Impl.mustache")) {
			ix = result.lastIndexOf(47.toChar())
			result = result.substring(0, ix) + "/impl" + result.substring(ix, result.length - 5) + "ServiceImpl.java"
		} else if (templateName.endsWith("Factory.mustache")) {
			ix = result.lastIndexOf(47.toChar())
			result =
				result.substring(0, ix) + "/factories" + result.substring(ix, result.length - 5) + "ServiceFactory" + ".java"
		} else if (templateName.endsWith("ClientService.mustache")) {
			ix = result.lastIndexOf(46.toChar())
			result = result.substring(0, ix) + "Client.java"
		} else if (templateName.endsWith("SecurityService.mustache")) {
			ix = result.lastIndexOf(46.toChar())
			result = result.substring(0, ix) + ".java"
		} else if (templateName.endsWith("DelegateServerService.mustache")) {
			ix = result.lastIndexOf(46.toChar())
			result = result.substring(0, ix) + "Delegator.java"
		} else if (templateName.endsWith("DelegateService.mustache")) {
			ix = result.lastIndexOf(46.toChar())
			result = result.substring(0, ix) + "Delegate.java"
		} else if (templateName.endsWith("Service.mustache")) {
			ix = result.lastIndexOf(46.toChar())
			result = result.substring(0, ix) + ".java"
		}
		return result
	}

	override fun toApiName(name: String): String {
		if (additionalProperties[SERVICE_NAME] != null) {
			return additionalProperties[SERVICE_NAME].toString()
		}
		return if (name.isEmpty()) {
			"DefaultApi"
		} else StringUtils.camelize(name)
	}

	companion object {
		private val log = LoggerFactory.getLogger(Jersey3ApiGenerator::class.java)
		private const val LIBRARY_NAME = "jersey3-api"
		private const val JERSEY2_TEMPLATE_FOLDER = "jersey3-v3template"
		private const val SERVICE_ADDRESS = "serviceAddress"
		private const val SERVICE_NAME = "serviceName"
		private const val SERVICE_PORT = "servicePort"
		private const val SERVICE_DEFAULT_URL = "serviceDefaultUrl"
		private const val ENUM_CAMEL_TO_SNAKE = "enum-camel-to-snake"

		// if this is set, then we always use this as the base path if it exists in all the paths in the set of operations
		private const val SERVICE_BASE = "serviceUrlBase"
		private const val PREFIX_ALL_PATHS_USING_GET = "prefixGetPath"
	}
}
