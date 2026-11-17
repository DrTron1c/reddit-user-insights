package app.userinsights.patches.reddit

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.util.logging.Logger

private const val EMBED_CLASS = "Lapp/userinsights/extension/EmbeddedPills;"

/**
 * The composable that draws a comment's author name and role badge (OP, MOD).
 * Found by its test tags, which stay the same across Reddit versions.
 */
internal object CommentAuthorFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("comment_author", "role_indicator")
)

/** toString of Compose's SnapshotMutableStateImpl. */
internal object MutableStateToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    strings = listOf("MutableState(value=")
)

@Suppress("unused")
val userInsightsEmbeddedPillsPatch = patch(
    name = "User insights pills in comments",
    description = "Draws the top subreddits inside the comment header, right after the name.",
    default = true
) {
    compatibleWith(Constants.COMPATIBILITY_REDDIT)

    dependsOn(userInsightsPatch)

    extendWith("extensions/extension.mpe")

    execute {
        val logger = Logger.getLogger("UserInsights")
        val method = CommentAuthorFingerprint.methodOrNull
        if (method == null) {
            logger.warning("Comment author composable not found: pills stay floating.")
            return@execute
        }
        val instructions = method.implementation!!.instructions.toList()

        fun isString(instruction: Instruction, value: String) =
            (instruction.opcode == Opcode.CONST_STRING || instruction.opcode == Opcode.CONST_STRING_JUMBO) &&
                ((instruction as ReferenceInstruction).reference as StringReference).string == value

        fun methodReference(instruction: Instruction) =
            (instruction as? ReferenceInstruction)?.reference as? MethodReference

        // The Text call that draws the name: first static call after the tag that takes a String first.
        val authorTagIndex = instructions.indexOfFirst { isString(it, "comment_author") }
        val textIndex = (authorTagIndex + 1 until instructions.size).firstOrNull { index ->
            val instruction = instructions[index]
            val reference = methodReference(instruction)
            instruction.opcode == Opcode.INVOKE_STATIC_RANGE && reference != null &&
                reference.returnType == "V" && reference.parameterTypes.size >= 15 &&
                reference.parameterTypes[0].toString() == "Ljava/lang/String;"
        }
        if (authorTagIndex < 0 || textIndex == null) {
            logger.warning("Name Text call not found: pills stay floating.")
            return@execute
        }
        val textReference = methodReference(instructions[textIndex])!!
        val textParameters = textReference.parameterTypes.map { it.toString() }
        // (text, modifier, color, ..., style, composer, changed, changed1, defaults)
        val composerParameter = textParameters.size - 4
        val styleParameter = textParameters.size - 5
        val composerType = textParameters[composerParameter]
        if (textParameters[1].startsWith("L").not() || textParameters[2] != "J") {
            logger.warning("Unexpected Text signature $textReference: pills stay floating.")
            return@execute
        }
        val offsets = textParameters.runningFold(0) { offset, type -> offset + if (type == "J" || type == "D") 2 else 1 }
        val textCall = method.getInstruction<RegisterRangeInstruction>(textIndex)
        val start = textCall.startRegister

        // The role badge call right after the tag "role_indicator", which also gets the composer.
        val roleTagIndex = (textIndex + 1 until instructions.size).firstOrNull { isString(instructions[it], "role_indicator") }
        val roleIndex = roleTagIndex?.let { tag ->
            (tag + 1 until instructions.size).firstOrNull { index ->
                val reference = methodReference(instructions[index])
                (instructions[index].opcode == Opcode.INVOKE_STATIC || instructions[index].opcode == Opcode.INVOKE_STATIC_RANGE) &&
                    reference != null && reference.returnType == "V" &&
                    reference.parameterTypes.any { it.toString() == composerType }
            }
        }
        if (roleIndex == null) {
            logger.warning("Role badge call not found: pills stay floating.")
            return@execute
        }
        val roleReference = methodReference(instructions[roleIndex])!!
        val roleParameters = roleReference.parameterTypes.map { it.toString() }
        val roleComposerParameter = roleParameters.indexOf(composerType)
        val roleOffsets = roleParameters.runningFold(0) { offset, type -> offset + if (type == "J" || type == "D") 2 else 1 }
        val composerRegister = when (val call = instructions[roleIndex]) {
            is RegisterRangeInstruction -> call.startRegister + roleOffsets[roleComposerParameter]
            is FiveRegisterInstruction -> listOf(call.registerC, call.registerD, call.registerE, call.registerF, call.registerG)[roleOffsets[roleComposerParameter]]
            else -> null
        }
        if (composerRegister == null) {
            logger.warning("Composer register not found: pills stay floating.")
            return@execute
        }

        // Values of the name's Text call, so the pills are drawn the same way (one line, same size).
        fun literalWrittenTo(register: Int): Long? {
            for (index in textIndex - 1 downTo maxOf(0, textIndex - 80)) {
                val instruction = instructions[index]
                if (instruction is OneRegisterInstruction && instruction.registerA == register) {
                    return when (instruction) {
                        is WideLiteralInstruction -> instruction.wideLiteral
                        is NarrowLiteralInstruction -> instruction.narrowLiteral.toLong()
                        else -> null
                    }
                }
            }
            return null
        }

        // Replace the placeholder with a call to Reddit's Text composable.
        val argumentRegisters = offsets.last()
        mutableClassDefBy(EMBED_CLASS).apply {
            val placeholder = methods.single { it.name == "drawText" }
            methods.remove(placeholder)

            // Parameters: composer, text, color (wide), style, modifier.
            val pComposer = argumentRegisters
            val pText = argumentRegisters + 1
            val pColor = argumentRegisters + 2
            val pStyle = argumentRegisters + 4
            val pModifier = argumentRegisters + 5

            val smali = StringBuilder()
            textParameters.forEachIndexed { index, type ->
                val register = offsets[index]
                val wide = type == "J" || type == "D"
                val isObject = type.startsWith("L") || type.startsWith("[")
                when (index) {
                    0 -> smali.appendLine("move-object/from16 v$register, v$pText")
                    1 -> smali.appendLine("move-object/from16 v$register, v$pModifier")
                        .appendLine("check-cast v$register, $type")
                    2 -> smali.appendLine("move-wide/from16 v$register, v$pColor")
                    styleParameter -> smali.appendLine("move-object/from16 v$register, v$pStyle")
                        .appendLine("check-cast v$register, $type")
                    composerParameter -> smali.appendLine("move-object/from16 v$register, v$pComposer")
                        .appendLine("check-cast v$register, $type")
                    textParameters.size - 3, textParameters.size - 2 ->
                        smali.appendLine("const/16 v$register, 0x0")
                    else -> {
                        val literal = literalWrittenTo(start + register) ?: 0L
                        when {
                            wide -> smali.appendLine("const-wide v$register, ${literal}L")
                            isObject -> smali.appendLine("const/16 v$register, 0x0")
                            else -> smali.appendLine("const v$register, $literal")
                        }
                    }
                }
            }
            smali.appendLine("invoke-static/range { v0 .. v${argumentRegisters - 1} }, $textReference")
            smali.appendLine("return-void")

            methods.add(
                ImmutableMethod(
                    type,
                    placeholder.name,
                    listOf(
                        ImmutableMethodParameter("Ljava/lang/Object;", null, null),
                        ImmutableMethodParameter("Ljava/lang/String;", null, null),
                        ImmutableMethodParameter("J", null, null),
                        ImmutableMethodParameter("Ljava/lang/Object;", null, null),
                        ImmutableMethodParameter("Ljava/lang/Object;", null, null)
                    ),
                    "V",
                    placeholder.accessFlags,
                    null,
                    null,
                    MutableMethodImplementation(argumentRegisters + 6)
                ).toMutable().apply {
                    addInstructions(0, smali.toString())
                }
            )

            // Compose's mutable state, so headers redraw when subreddits arrive.
            val stateBase = MutableStateToStringFingerprint.originalClassDefOrNull
            val stateClass = stateBase?.let { base ->
                classDefByOrNull { classDef ->
                    (classDef.type == base.type || classDef.superclass == base.type) &&
                        (classDef.accessFlags and AccessFlags.ABSTRACT.value) == 0 &&
                        classDef.methods.any {
                            it.name == "<init>" && it.parameterTypes.size == 2 &&
                                it.parameterTypes[0].toString() == "Ljava/lang/Object;"
                        }
                }
            }
            val policyType = stateClass?.methods?.first {
                it.name == "<init>" && it.parameterTypes.size == 2 && it.parameterTypes[0].toString() == "Ljava/lang/Object;"
            }?.parameterTypes?.get(1)?.toString()

            if (stateClass != null && policyType != null) {
                replaceStringPlaceholder(this, "stateClassName", javaName(stateClass.type))
                replaceStringPlaceholder(this, "policyClassName", javaName(policyType))
            } else {
                logger.warning("Compose state not found: comment headers show new pills when redrawn.")
            }
        }

        // After the role badge: draw the pills. (Inserted first, as it is further down.)
        method.addInstructions(
            roleIndex + 1,
            "invoke-static/range { v$composerRegister .. v$composerRegister }, $EMBED_CLASS->drawTags(Ljava/lang/Object;)V"
        )

        // Before the name is drawn: remember the name, its style and modifier.
        val nameRegister = start + offsets[0]
        val modifierRegister = start + offsets[1]
        val styleRegister = start + offsets[styleParameter]
        method.addInstructions(
            textIndex,
            """
                invoke-static/range { v$nameRegister .. v$nameRegister }, $EMBED_CLASS->setAuthor(Ljava/lang/String;)V
                invoke-static/range { v$styleRegister .. v$styleRegister }, $EMBED_CLASS->setStyle(Ljava/lang/Object;)V
                invoke-static/range { v$modifierRegister .. v$modifierRegister }, $EMBED_CLASS->setModifier(Ljava/lang/Object;)V
            """
        )
    }
}

private fun javaName(descriptor: String) = descriptor.removePrefix("L").removeSuffix(";").replace('/', '.')

private fun replaceStringPlaceholder(
    classDef: app.morphe.patcher.util.proxy.mutableTypes.MutableClass,
    name: String,
    value: String
) {
    val placeholder = classDef.methods.single { it.name == name }
    classDef.methods.remove(placeholder)
    classDef.methods.add(
        ImmutableMethod(
            classDef.type,
            name,
            listOf(),
            "Ljava/lang/String;",
            placeholder.accessFlags,
            null,
            null,
            MutableMethodImplementation(1)
        ).toMutable().apply {
            addInstructions(
                0,
                """
                    const-string v0, "$value"
                    return-object v0
                """
            )
        }
    )
}
