package org.jetbrains.kotlin.native.interop.gen.wasm

import org.jetbrains.kotlin.konan.file.File
import org.jetbrains.kotlin.native.interop.gen.wasm.idl.*
import org.jetbrains.kotlin.native.interop.tool.CommonInteropArguments
import org.jetbrains.kotlin.native.interop.tool.parseCommandLine

fun kotlinHeader(packageName: String): String {
    return  "package $packageName\n" +
            "import kotlinx.wasm.jsinterop.*\n"
}

fun Type.toKotlinType(argName: String? = null): String = when (this) {
    is idlVoid -> "Unit"
    is idlInt -> "Int"
    is idlFloat -> "Float"
    is idlDouble -> "Double"
    is idlString -> "String"
    is idlObject -> "JsValue"
    is idlFunction -> "KtFunction<R${argName!!}>"
    is idlInterfaceRef -> name
    else -> error("Unexpected type")
}

fun Arg.wasmMapping(): String = when (type) {
    is idlVoid -> error("An arg can not be idlVoid")
    is idlInt -> name
    is idlFloat -> name
    is idlDouble -> "doubleUpper($name), doubleLower($name)"
    is idlString -> "stringPointer($name), stringLengthBytes($name)"
    is idlObject -> TODO("implement me")
    is idlFunction -> "wrapFunction<R$name>($name), ArenaManager.currentArena"
    is idlInterfaceRef -> TODO("Implement me")
    else -> error("Unexpected type")
}

fun Type.wasmReturnArg(): String =
    when (this) {
        is idlVoid -> "ArenaManager.currentArena" // TODO: optimize.
        is idlInt -> "ArenaManager.currentArena"
        is idlFloat -> "ArenaManager.currentArena"
        is idlDouble -> "resultPtr"
        is idlString -> "ArenaManager.currentArena"
        is idlObject -> "ArenaManager.currentArena"
        is idlFunction -> "ArenaManager.currentArena"
        is idlInterfaceRef -> "ArenaManager.currentArena"
        else -> error("Unexpected type")
    }
val Operation.wasmReturnArg: String get() = returnType.wasmReturnArg()
val Attribute.wasmReturnArg: String get() = type.wasmReturnArg()

fun Arg.wasmArgNames(): List<String> = when (type) {
    is idlVoid -> error("An arg can not be idlVoid")
    is idlInt -> listOf(name)
    is idlFloat -> listOf(name)
    is idlDouble -> listOf("${name}Upper", "${name}Lower")
    is idlString -> listOf("${name}Ptr", "${name}Len")
    is idlObject -> TODO("implement me (idlObject)")
    is idlFunction -> listOf("${name}Index", "${name}ResultArena")
    is idlInterfaceRef -> TODO("Implement me (idlInterfaceRef)")
    else -> error("Unexpected type")
}

fun Type.wasmReturnMapping(value: String): String = when (this) {
    is idlVoid -> ""
    is idlInt -> value
    is idlFloat -> value
    is idlDouble -> value
    is idlString -> "TODO(\"Implement me\")"
    is idlObject -> "JsValue(ArenaManager.currentArena, $value)"
    is idlFunction -> "TODO(\"Implement me\")"
    is idlInterfaceRef -> "$name(ArenaManager.currentArena, $value)"
    else -> error("Unexpected type")
}

fun wasmFunctionName(functionName: String, interfaceName: String)
    = "knjs__${interfaceName}_$functionName"

fun wasmSetterName(propertyName: String, interfaceName: String)
    = "knjs_set__${interfaceName}_$propertyName"

fun wasmGetterName(propertyName: String, interfaceName: String)
    = "knjs_get__${interfaceName}_$propertyName"

val Operation.kotlinTypeParameters: String get() {
    val lambdaRetTypes = args.filter { it.type is idlFunction }
        .map { "R${it.name}" }. joinToString(", ")
    return if (lambdaRetTypes == "") "" else "<$lambdaRetTypes>"
}

val Interface.wasmReceiverArgs get() =
    if (isGlobal) emptyList()
    else listOf("this.arena", "this.index")

fun Member.wasmReceiverArgs(parent: Interface) =
    if (isStatic) emptyList()
    else parent.wasmReceiverArgs

val Type.allocateReturnDestination get() =
    when (this) {
        is idlDouble -> "    val resultPtr = allocateDouble()\n"
        else -> ""
    }
val Operation.allocateReturnDestination get() = returnType.allocateReturnDestination
val Attribute.allocateReturnDestination get() = type.allocateReturnDestination

val Type.deallocateReturnDestination get() =
    when (this) {
        is idlDouble -> "    deallocateDouble(doublePtr)\n"
        else -> ""
    }
val Operation.deallocateReturnDestination get() = returnType.deallocateReturnDestination
val Attribute.deallocateReturnDestination get() = type.deallocateReturnDestination

fun Type.generateKotlinCall(name: String, wasmArgList: String) =
    "$name($wasmArgList)" 

fun Type.generateKotlinCallWithReturn(name: String, wasmArgList: String) =
    when(this) {
        is idlVoid ->   "    ${generateKotlinCall(name, wasmArgList)}\n"
        is idlDouble -> "    val doublePtr = ${generateKotlinCall(name, wasmArgList)}\n" +
                        "    val wasmRetVal = heapDouble(doublePtr)\n"

        else ->         "    val wasmRetVal = ${generateKotlinCall(name, wasmArgList)}\n"
    }

fun Operation.generateKotlinCallWithReturn(parent_name: String, wasmArgList: String) =
    returnType.generateKotlinCallWithReturn(
        wasmFunctionName(name, parent_name), 
        wasmArgList)

fun Attribute.generateKotlinGetterCallWithReturn(parent_name: String, wasmArgList: String) =
    type.generateKotlinCallWithReturn(
        wasmGetterName(name, parent_name), 
        wasmArgList)

fun Operation.generateKotlin(parent: Interface): String {
    val argList = args.map {
        "${it.name}: ${it.type.toKotlinType(it.name)}"
    }.joinToString(", ")

    val wasmArgList = (wasmReceiverArgs(parent) + args.map(Arg::wasmMapping) + wasmReturnArg).joinToString(", ")

    // TODO: there can be multiple Rs.
    return "  fun $kotlinTypeParameters $name(" + 
    argList + 
    "): ${returnType.toKotlinType()} {\n" +
        allocateReturnDestination +
        generateKotlinCallWithReturn(parent.name, wasmArgList) +
        deallocateReturnDestination +
    "    return ${returnType.wasmReturnMapping("wasmRetVal")}\n"+
    "  }\n\n"
}

fun Attribute.generateKotlinSetter(parent: Interface): String {
    val kotlinType = type.toKotlinType(name)
    return "    set(value: $kotlinType) {\n" +
    "      ${wasmSetterName(name, parent.name)}(" +
        (wasmReceiverArgs(parent) + Arg("value", type).wasmMapping()).joinToString(", ") +
        ")\n" + 
    "    }\n\n"
}

fun Attribute.generateKotlinGetter(parent: Interface): String {
    val wasmArgList = (wasmReceiverArgs(parent) + wasmReturnArg).joinToString(", ")
    return "    get() {\n" +
        allocateReturnDestination +
        generateKotlinGetterCallWithReturn(parent.name, wasmArgList) +
        deallocateReturnDestination +
    //"      val wasmRetVal = ${wasmGetterName(name, parent.name)}(${(wasmReceiverArgs(parent) + wasmReturnArg).joinToString(", ")})\n" + 
    "      return ${type.wasmReturnMapping("wasmRetVal")}\n"+
    "    }\n\n"
}

fun Attribute.generateKotlin(parent: Interface): String {
    val kotlinType = type.toKotlinType(name)
    val varOrVal = if (readOnly) "val" else "var"
    return "  $varOrVal $name: $kotlinType\n" +
    generateKotlinGetter(parent) +
    if (!readOnly) generateKotlinSetter(parent) else ""
}

val Interface.wasmTypedReceiverArgs get() =
    if (isGlobal) emptyList()
    else listOf("arena: Int", "index: Int")

fun Member.wasmTypedReceiverArgs(parent: Interface) =
    if (isStatic) emptyList() else parent.wasmTypedReceiverArgs

fun Operation.generateWasmStub(parent: Interface): String {
    val wasmName = wasmFunctionName(this.name, parent.name)
    val allArgs = (wasmTypedReceiverArgs(parent) + args.toList().wasmTypedMapping() + wasmTypedReturnMapping).joinToString(", ")
    return "@SymbolName(\"$wasmName\")\n" +
    "external public fun $wasmName($allArgs): ${returnType.wasmReturnTypeMapping()}\n\n"
}
fun Attribute.generateWasmSetterStub(parent: Interface): String {
    val wasmSetter = wasmSetterName(this.name, parent.name)
    val allArgs = (wasmTypedReceiverArgs(parent) + Arg("value", this.type).wasmTypedMapping()).joinToString(", ")
    return "@SymbolName(\"$wasmSetter\")\n" +
    "external public fun $wasmSetter($allArgs): Unit\n\n"
}
fun Attribute.generateWasmGetterStub(parent: Interface): String {
    val wasmGetter = wasmGetterName(this.name, parent.name)
    val allArgs = (wasmTypedReceiverArgs(parent) + wasmTypedReturnMapping).joinToString(", ")
    return "@SymbolName(\"$wasmGetter\")\n" +
    "external public fun $wasmGetter($allArgs): Int\n\n"
}
fun Attribute.generateWasmStubs(parent: Interface) =
    generateWasmGetterStub(parent) +
    if (!readOnly) generateWasmSetterStub(parent) else ""

// TODO: consider using virtual mathods
fun Member.generateKotlin(parent: Interface): String = when (this) {
    is Operation -> this.generateKotlin(parent)
    is Attribute -> this.generateKotlin(parent)
    else -> error("Unexpected member")
}

// TODO: consider using virtual mathods
fun Member.generateWasmStub(parent: Interface) =
    when (this) {
        is Operation -> this.generateWasmStub(parent)
        is Attribute -> this.generateWasmStubs(parent)
        else -> error("Unexpected member")

    }

fun Arg.wasmTypedMapping()
    = this.wasmArgNames().map { "$it: Int" } .joinToString(", ")

fun Type.wasmTypedReturnMapping(): String =
    when (this) {
        idlDouble -> "resultPtr: Int"
        else -> "resultArena: Int"
            // TODO: all types.
    }

val Operation.wasmTypedReturnMapping get() = returnType.wasmTypedReturnMapping()

val Attribute.wasmTypedReturnMapping get() = type.wasmTypedReturnMapping()

fun List<Arg>.wasmTypedMapping()
    = this.map(Arg::wasmTypedMapping)

// TODO: more complex return types, such as returning a pair of Ints
// will require a more complex approach.
fun Type.wasmReturnTypeMapping()
    = if (this == idlVoid) "Unit" else "Int"

fun Interface.generateMemberWasmStubs() =
    members.map {
        it.generateWasmStub(this)
    }.joinToString("")

fun Interface.generateKotlinMembers() =
    members.filterNot { it.isStatic } .map {
        it.generateKotlin(this)
    }.joinToString("") 

fun Interface.generateKotlinCompanion() =
    "    companion object {\n" +
        members.filter { it.isStatic } .map {
            it.generateKotlin(this)
        }.joinToString("") +
    "    }\n" 

fun Interface.generateKotlinClassHeader() =
    "open class $name(arena: Int, index: Int): JsValue(arena, index) {\n" +
    "  constructor(jsValue: JsValue): this(jsValue.arena, jsValue.index)\n"

fun Interface.generateKotlinClassFooter() =
    "}\n"

fun Interface.generateKotlinClassConverter() =
    "val JsValue.as$name: $name\n" +
    "  get() {\n" +
    "    return $name(this.arena, this.index)\n"+
    "  }\n"

fun Interface.generateKotlin(): String {

    fun String.skipForGlobal() = 
        if (! this@generateKotlin.isGlobal) this 
        else ""

    return generateMemberWasmStubs() + 
        generateKotlinClassHeader().skipForGlobal() +
        generateKotlinMembers() + 
        generateKotlinCompanion().skipForGlobal() +
        generateKotlinClassFooter().skipForGlobal() +
        generateKotlinClassConverter().skipForGlobal()
}

fun generateKotlin(pkg: String, interfaces: List<Interface>) =
    kotlinHeader(pkg) + 
    interfaces.map {
        it.generateKotlin()
    }.joinToString("\n") +
    if (pkg == "kotlinx.interop.wasm.dom")  // TODO: make it a general solution.
        "fun <R> setInterval(interval: Int, lambda: KtFunction<R>) = setInterval(lambda, interval)\n"
    else ""

/////////////////////////////////////////////////////////

fun Arg.composeWasmArgs(): String = when (type) {
    is idlVoid -> error("An arg can not be idlVoid")
    is idlInt -> ""
    is idlFloat -> ""
    is idlDouble ->
        "    var $name = twoIntsToDouble(${name}Upper, ${name}Lower);\n"
    is idlString ->
        "    var $name = toUTF16String(${name}Ptr, ${name}Len);\n"
    is idlObject -> TODO("implement me")
    is idlFunction ->
        "    var $name = konan_dependencies.env.Konan_js_wrapLambda(lambdaResultArena, ${name}Index);\n"

    is idlInterfaceRef -> TODO("Implement me")
    else -> error("Unexpected type")
}

val Interface.receiver get() =
    if (isGlobal) "" else  "kotlinObject(arena, obj)."

fun Member.receiver(parent: Interface) =
    if (isStatic) "${parent.name}." else parent.receiver

val Interface.wasmReceiverArgName get() =
    if (isGlobal) emptyList() else listOf("arena", "obj")

fun Member.wasmReceiverArgName(parent: Interface) =
    if (isStatic) emptyList() else parent.wasmReceiverArgName

val Operation.wasmReturnArgName get() =
    returnType.wasmReturnArgName

val Attribute.wasmReturnArgName get() =
    type.wasmReturnArgName

val Type.wasmReturnArgName get() =
    when (this) {
        is idlVoid -> emptyList()
        is idlInt -> emptyList()
        is idlFloat -> emptyList()
        is idlDouble -> listOf("resultPtr")
        is idlString -> listOf("resultArena")
        is idlObject -> listOf("resultArena")
        is idlInterfaceRef -> listOf("resultArena")
        else -> error("Unexpected type: $this")
    }

val Type.wasmReturnExpression get() =
    when(this) {
        is idlVoid -> ""
        is idlInt -> "result"
        is idlFloat -> "result" // TODO: can we really pass floats as is?
        is idlDouble -> "doubleToHeap(result, resultPtr)"
        is idlString -> "toArena(resultArena, result)"
        is idlObject -> "toArena(resultArena, result)"
        is idlInterfaceRef -> "toArena(resultArena, result)"
        else -> error("Unexpected type: $this")
    }

fun Operation.generateJs(parent: Interface): String {
    val allArgs = wasmReceiverArgName(parent) + args.map { it.wasmArgNames() }.flatten() + wasmReturnArgName
    val wasmMapping = allArgs.joinToString(", ")
    val argList = args.map { it.name }. joinToString(", ")
    val composedArgsList = args.map { it.composeWasmArgs() }. joinToString("")

    return "\n  ${wasmFunctionName(this.name, parent.name)}: function($wasmMapping) {\n" +
        composedArgsList +
        "    var result = ${receiver(parent)}$name($argList);\n" +
        "    return ${returnType.wasmReturnExpression};\n" +
    "  }"
}

fun Attribute.generateJsSetter(parent: Interface): String {
    val valueArg = Arg("value", type)
    val allArgs = wasmReceiverArgName(parent) + valueArg.wasmArgNames()
    val wasmMapping = allArgs.joinToString(", ")
    return "\n  ${wasmSetterName(name, parent.name)}: function($wasmMapping) {\n" +
        valueArg.composeWasmArgs() +
        "    ${receiver(parent)}$name = value;\n" +
    "  }"
}

fun Attribute.generateJsGetter(parent: Interface): String {
    val allArgs = wasmReceiverArgName(parent) + wasmReturnArgName
    val wasmMapping = allArgs.joinToString(", ")
    return "\n  ${wasmGetterName(name, parent.name)}: function($wasmMapping) {\n" +
        "    var result = ${receiver(parent)}$name;\n" +
        "    return ${type.wasmReturnExpression};\n" +
    "  }"
}

fun Attribute.generateJs(parent: Interface) =
    generateJsGetter(parent) + 
    if (!readOnly) ",\n${generateJsSetter(parent)}" else ""

fun Member.generateJs(parent: Interface): String = when (this) {
    is Operation -> this.generateJs(parent)
    is Attribute -> this.generateJs(parent)
    else -> error("Unexpected member")
}

fun generateJs(interfaces: List<Interface>): String =
    "konan.libraries.push ({\n" +
    interfaces.map { interf ->
        interf.members.map { member -> 
            member.generateJs(interf) 
        }
    }.flatten() .joinToString(",\n") + 
    "\n})\n"

const val idlMathPackage = "kotlinx.interop.wasm.math"
const val idlDomPackage = "kotlinx.interop.wasm.dom"

fun processIdlLib(args: Array<String>) {
    val arguments = parseCommandLine(args, CommonInteropArguments())
    // TODO: Refactor me.
    val userDir = System.getProperty("user.dir")
    val ktGenRoot = File(arguments.generated ?: userDir).mkdirs()
    val nativeLibsDir = File(arguments.natives ?: userDir).mkdirs()

    val idl = when (arguments.pkg) {
        idlMathPackage-> idlMath
        idlDomPackage -> idlDom
        else -> throw IllegalArgumentException("Please choose either $idlMathPackage or $idlDomPackage for -pkg argument")
    }

    File(ktGenRoot, "kotlin_stubs.kt").writeText(generateKotlin(arguments.pkg!!, idl))
    File(nativeLibsDir, "js_stubs.js").writeText(generateJs(idl))
    File(arguments.manifest!!).writeText("") // The manifest is currently unused for wasm.
}
