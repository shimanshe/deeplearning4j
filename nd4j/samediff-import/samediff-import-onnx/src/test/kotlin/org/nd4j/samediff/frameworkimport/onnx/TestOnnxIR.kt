/*
 *  ******************************************************************************
 *  *
 *  *
 *  * This program and the accompanying materials are made available under the
 *  * terms of the Apache License, Version 2.0 which is available at
 *  * https://www.apache.org/licenses/LICENSE-2.0.
 *  *
 *  *  See the NOTICE file distributed with this work for additional
 *  *  information regarding copyright ownership.
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  * License for the specific language governing permissions and limitations
 *  * under the License.
 *  *
 *  * SPDX-License-Identifier: Apache-2.0
 *  *****************************************************************************
 */

package org.nd4j.samediff.frameworkimport.onnx


import onnx.Onnx
import org.junit.Ignore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.nd4j.common.util.ArrayUtil
import org.nd4j.ir.OpNamespace
import org.nd4j.linalg.api.buffer.DataType
import org.nd4j.linalg.api.ndarray.INDArray
import org.nd4j.linalg.factory.Nd4j
import org.nd4j.samediff.frameworkimport.ImportGraph
import org.nd4j.samediff.frameworkimport.onnx.definitions.OnnxOpDeclarations
import org.nd4j.samediff.frameworkimport.onnx.definitions.registry
import org.nd4j.samediff.frameworkimport.onnx.ir.OnnxIRGraph
import org.nd4j.samediff.frameworkimport.onnx.ir.OnnxIRGraphRunner
import kotlin.test.assertTrue

data class OnnxGraphInput(val graphDef: Onnx.GraphProto, val inputNames: List<String>, val outputNames: List<String>,
                          val inputArrays: Map<String, INDArray>, val dynamicArrays: Map<String, INDArray>)


class TestOnnxIR {
    val declarations = OnnxOpDeclarations



    @Test
    fun testInputOutputNames() {
        val onnxOpRegistry = registry()
        val onnxOpNames = onnxOpRegistry.inputFrameworkOpNames()
        val nd4jOpNames = onnxOpRegistry.nd4jOpNames()
        onnxOpRegistry.mappingProcessNames().map {
            onnxOpRegistry.lookupOpMappingProcess(it)
        }.forEach {
            println("Beginning processing of op ${it.inputFrameworkOpName()} and nd4j op ${it.opName()}")
            assertTrue(onnxOpNames.contains(it.inputFrameworkOpName()))
            assertTrue(nd4jOpNames.contains(it.opName()))
            val nd4jOpDef = onnxOpRegistry.lookupNd4jOpDef(it.opName())
            val onnxOpDef = onnxOpRegistry.lookupInputFrameworkOpDef(it.inputFrameworkOpName())
            val inputNameArgDefs = nd4jOpDef.argDescriptorList.filter {
                    argDef -> argDef.argType == OpNamespace.ArgDescriptor.ArgType.INPUT_TENSOR
            }.map { argDef -> argDef.name }

            val inputFrameworkOpDefNames = onnxOpDef.inputList

            val nd4jArgDefNames = nd4jOpDef.argDescriptorList.map { nd4jArgDef -> nd4jArgDef.name }
            val onnxAttrNames = onnxOpDef.attributeList.map { onnxAttr -> onnxAttr.name }
            it.tensorMappingRules().forEach { tensorRules ->
                println("Running tensor mapping rule ${tensorRules.name()} for op ${it.inputFrameworkOpName()} and nd4j op name ${it.opName()}")
                run {
                    tensorRules.mappingNamesToPerform().forEach { tensorRule ->
                        run {
                            println("Testing assertion for nd4j name ${tensorRule.key} and input name ${tensorRule.value}")
                            assertTrue(inputNameArgDefs.contains(tensorRule.key)) ?: error("Failed on inputArgName ${tensorRule.key}")
                            assertTrue(inputFrameworkOpDefNames.contains(tensorRule.value)) ?: error("Failed on inputArgName ${tensorRule.value}")
                        }

                    }
                }

            }

            println("Running attribute mapping rules for ${it.opName()} and input op name ${it.inputFrameworkOpName()}")
            it.attributeMappingRules().forEach { attrRule ->
                run {
                    attrRule.mappingNamesToPerform().forEach { attrMapping ->
                        run {
                            println("Testing nd4j name  ${attrMapping.key} and input framework name ${attrMapping.value}")
                            assertTrue(nd4jArgDefNames.contains(attrMapping.key) || inputNameArgDefs.contains(attrMapping.key))
                            assertTrue(onnxAttrNames.contains(attrMapping.value) || inputFrameworkOpDefNames.contains(attrMapping.value))

                        }

                    }
                }
            }

        }
    }



    @Test
    fun testOpExecutionHooks() {
        val onnxOpRegistry = registry()
        val importGraph = ImportGraph<Onnx.GraphProto,Onnx.NodeProto,Onnx.NodeProto,Onnx.TensorProto,Onnx.AttributeProto,Onnx.AttributeProto,Onnx.TensorProto.DataType>()
        val inputTensor = Nd4j.ones(1,3,5,5)
        val graphToRun = GraphProto {
            Input(createValueInfoFromTensor(inputTensor,"x",true))


            //Initializer(convertedTensor)
            Node(NodeProto {
                Input("x")
                Output("y")
                name = "y"
                opType = "GlobalAveragePool"
            })

            Output(createValueInfoFromTensor(inputTensor,"y",false))
        }


        val onnxIRGraph = OnnxIRGraph(graphToRun,onnxOpRegistry)
        val onnxGraphRunner = OnnxIRGraphRunner(onnxIRGraph,listOf("x"),listOf("y"))
        val importedGraph = importGraph.importGraph(onnxIRGraph,null,null,HashMap(),onnxOpRegistry)
        val inputs = mapOf("x" to inputTensor)
        val assertion = onnxGraphRunner.run(inputs)
        val result = importedGraph.output(inputs,"y")
        assertEquals(assertion,result)
    }


    @Test
    fun testExpand() {
        val declarations = OnnxOpDeclarations
        val onnxOpRegistry = registry()
        val shape = longArrayOf(3,1)
        val newShape = longArrayOf(2,1,6)
        val inputNewShape = Nd4j.create(Nd4j.createBuffer(newShape))
        val inputs = mapOf("data" to Nd4j.arange(1.0, ArrayUtil.prod(*shape).toDouble() + 1.0).reshape(*shape),
            "newShape" to inputNewShape)
        val inputNames = listOf("data","newShape")
        val outputs = listOf("expanded")
        val graph = createSingleNodeGraph(inputs,"Expand", emptyMap(),outputs,inputNames)
        runAssertion(graph,inputs,outputs)

    }

    @Test
    fun testSlice() {

        /**
         * Note that this test case is manual due to subtle differences in
         * how onnxruntime and tensorflow appear to interpret their nearest neighbor results.
         * In our test case here, we are verifying against tensorflow-onnx as the implementation.
         *
         */
        Nd4j.getExecutioner().enableDebugMode(true)
        Nd4j.getExecutioner().enableVerboseMode(true)

        val x = Nd4j.linspace(1,1000,1000).reshape(20,10,5)
        val starts = Nd4j.zeros(2).castTo(DataType.INT64)
        val ends = Nd4j.create(Nd4j.createBuffer(longArrayOf(3,10))).reshape(2)
        val axes = Nd4j.create(Nd4j.createBuffer(longArrayOf(0,1))).reshape(2)
        val steps = Nd4j.ones(2).castTo(DataType.INT64).reshape(2)

        val input = mapOf("x" to x,"starts" to starts,"ends" to ends,"axes" to axes,"steps" to steps)

        val outputs = listOf("y")
        val attributes = emptyMap<String,Any>()
        val inputs = listOf("x","starts","ends","axes","steps")
        val graph = createSingleNodeGraph(input,"Slice",attributes,outputs,inputs)
        assertEquals(input.size,graph.inputCount)
        assertEquals(1,graph.outputCount)
        runAssertion(graph,input,outputs)
    }

    fun runAssertion(graph: Onnx.GraphProto,input: Map<String,INDArray>,outputs: List<String>) {
        val declarations = OnnxOpDeclarations
        val onnxOpRegistry = registry()

        val onnxIRGraph = OnnxIRGraph(graph,onnxOpRegistry)
        val onnxGraphRunner = OnnxIRGraphRunner(onnxIRGraph,input.keys.toList(),outputs)
        val assertion = onnxGraphRunner.run(input)
        val importGraph = ImportGraph<Onnx.GraphProto,Onnx.NodeProto,Onnx.NodeProto,Onnx.TensorProto,Onnx.AttributeProto,Onnx.AttributeProto,Onnx.TensorProto.DataType>()

        val importedGraph = importGraph.importGraph(onnxIRGraph,null,null, convertToOnnxTensors(input),onnxOpRegistry)
        val result = importedGraph.output(input,outputs)
        assertEquals(assertion,result)
    }


    @Test
    fun testClip() {
        val declarations = OnnxOpDeclarations
        val inputs = mutableMapOf("input" to Nd4j.linspace(1,4,4).castTo(DataType.DOUBLE),
            "min" to Nd4j.scalar(1.0).castTo(DataType.DOUBLE), "max" to Nd4j.scalar(2.0).castTo(DataType.DOUBLE))
        val output = listOf("output")
        val createdGraph = createSingleNodeGraph(inputs,"Clip",emptyMap(),output,inputs.keys.toList())
        runAssertion(createdGraph,inputs,output)

    }

    @Test
    fun testNonZero() {
        val declarations = OnnxOpDeclarations
        val inputs = mutableMapOf("input" to Nd4j.linspace(1,4,4).castTo(DataType.DOUBLE))
        val onnxOpRegistry = registry()

        val output = listOf("output")
        val createdGraph = createSingleNodeGraph(inputs,"NonZero",emptyMap(),output,inputs.keys.toList(),templateTensor = Nd4j.ones(DataType.INT64))
        val importGraph = ImportGraph<Onnx.GraphProto,Onnx.NodeProto,Onnx.NodeProto,Onnx.TensorProto,Onnx.AttributeProto,Onnx.AttributeProto,Onnx.TensorProto.DataType>()
        val onnxIRGraph = OnnxIRGraph(createdGraph,onnxOpRegistry)
        val importedGraph = importGraph.importGraph(onnxIRGraph,null,null, convertToOnnxTensors(inputs),onnxOpRegistry)
        val result = importedGraph.output(inputs,output)

        //runAssertion(createdGraph,inputs,output)

    }


    @Test
    fun testIf() {
        val thenOut = convertToOnnxTensor(Nd4j.ones(DataType.FLOAT,5),"then_out")
        val elseOut = convertToOnnxTensor(Nd4j.ones(DataType.FLOAT,5),"else_out")
        val x = Nd4j.linspace(1,5,5).castTo(DataType.FLOAT)
        val y = Nd4j.create(floatArrayOf(5.0f,4.0f,3.0f,2.0f,1.0f))
        val elseGraph = createSingleNodeGraph(emptyMap(),"Constant",mapOf("value" to elseOut),listOf("else_out"),listOf(),x)
        val thenGraph = createSingleNodeGraph(emptyMap(),"Constant",mapOf("value" to thenOut),listOf("then_out"),listOf(),x)
        val thenGraphAttr = AttributeProto {
            name = "then_branch"
            g = thenGraph
            type = Onnx.AttributeProto.AttributeType.GRAPH
        }
        val elseAttr = AttributeProto {
            name = "else_branch"
            g = elseGraph
            type = Onnx.AttributeProto.AttributeType.GRAPH
        }
        val ifNode = NodeProto {
            opType = "If"
            name = "ifNode"
            Input("cond")
            Output("res")
            Attribute(thenGraphAttr)
            Attribute(elseAttr)
        }

        val graph = GraphProto {
            name = "ifGraph"
            Input(createValueInfoFromTensor(Nd4j.ones(1).castTo(DataType.BOOL),"cond",true))
            Node(ifNode)
            Output(createValueInfoFromTensor(y,"res",true))
        }

        runAssertion(graph,mapOf("cond" to (Nd4j.ones(1).castTo(DataType.BOOL))),listOf("res"))
    }



    @Test
    fun testRoiAligned() {
        val xArr =   arrayOf(
            doubleArrayOf(
                0.2764,
                0.7150,
                0.1958,
                0.3416,
                0.4638,
                0.0259,
                0.2963,
                0.6518,
                0.4856,
                0.7250,
            ),
            doubleArrayOf(
                0.9637,
                0.0895,
                0.2919,
                0.6753,
                0.0234,
                0.6132,
                0.8085,
                0.5324,
                0.8992,
                0.4467,
            ),
            doubleArrayOf(
                0.3265,
                0.8479,
                0.9698,
                0.2471,
                0.9336,
                0.1878,
                0.4766,
                0.4308,
                0.3400,
                0.2162,
            ),
            doubleArrayOf(
                0.0206,
                0.1720,
                0.2155,
                0.4394,
                0.0653,
                0.3406,
                0.7724,
                0.3921,
                0.2541,
                0.5799,
            ),
            doubleArrayOf(
                0.4062,
                0.2194,
                0.4473,
                0.4687,
                0.7109,
                0.9327,
                0.9815,
                0.6320,
                0.1728,
                0.6119,
            ),
            doubleArrayOf(
                0.3097,
                0.1283,
                0.4984,
                0.5068,
                0.4279,
                0.0173,
                0.4388,
                0.0430,
                0.4671,
                0.7119,
            ),
            doubleArrayOf(
                0.1011,
                0.8477,
                0.4726,
                0.1777,
                0.9923,
                0.4042,
                0.1869,
                0.7795,
                0.9946,
                0.9689,
            ),
            doubleArrayOf(
                0.1366,
                0.3671,
                0.7011,
                0.6234,
                0.9867,
                0.5585,
                0.6985,
                0.5609,
                0.8788,
                0.9928,
            ),
            doubleArrayOf(
                0.5697,
                0.8511,
                0.6711,
                0.9406,
                0.8751,
                0.7496,
                0.1650,
                0.1049,
                0.1559,
                0.2514,
            ),
            doubleArrayOf(
                0.7012,
                0.4056,
                0.7879,
                0.3461,
                0.0415,
                0.2998,
                0.5094,
                0.3727,
                0.5482,
                0.0502,
            ))
        val roiX = Nd4j.create(xArr).reshape(1,1,10,10).castTo(DataType.FLOAT)
        val rois = Nd4j.create(Nd4j.createBuffer(longArrayOf(0,0,9,9,0,5,4,9,5,5,9,9))).reshape(3,4).castTo(DataType.FLOAT)
        val batchIndices = Nd4j.create(Nd4j.createBuffer(longArrayOf(0,0,0))).reshape(3)
        val y = Nd4j.create(Nd4j.createBuffer(doubleArrayOf(0.4664,0.4466,0.3405,0.5688,0.6068,0.3714,0.4296,0.3835,0.5562,0.351
            ,0.2768,0.4883,0.5222,0.5528,0.4171,0.4713,0.4844,0.6904,0.492,0.8774
            ,0.6239,0.7125,0.6289,0.3355,0.3495,0.3022,0.4305,0.4696,0.3978,0.5423
            ,0.3656,0.705,0.5165,0.3172,0.7015,0.2912,0.5059,0.6476,0.6235,0.8299
            ,0.5916,0.7389,0.7048,0.8372,0.8893,0.6227,0.6153,0.7097,0.6154,0.4585
            ,0.2384,0.3379,0.3717,0.61,0.7601,0.3767,0.3785,0.7147,0.9243,0.9727
            ,0.5749,0.5826,0.5709,0.7619,0.877,0.5355,0.2566,0.2141,0.2796,0.36
            ,0.4365,0.3504,0.2887,0.3661,0.2349))).reshape(3,1,5,5)

        val outputs = listOf("y")
        val inputs = mapOf("X" to roiX,"rois" to rois,"batch_indices" to batchIndices)
        val attributes = mapOf("spatial_scale" to 1.0f,"output_height" to 5,"output_width" to 5,"sampling_ratio" to 2)
        val createdGraph = createSingleNodeGraph(inputs,"RoiAlign",attributes,outputs,inputs.keys.toList())
        runAssertion(createdGraph,inputs,outputs)

    }

    @Test
    fun testMaximum() {
        val declarations = OnnxOpDeclarations
        val inputs = mutableMapOf<String,INDArray>()
        for(i in 0 until 5) {
            inputs["$i"] = Nd4j.zeros(2).addi(i)
        }

        val output = listOf("output")
        val createdGraph = createSingleNodeGraph(inputs,"Max",emptyMap(),output,inputs.keys.toList())
        runAssertion(createdGraph,inputs,output)

    }


    @Test
    fun testMinimum() {
        val declarations = OnnxOpDeclarations
        val inputs = mutableMapOf<String,INDArray>()
        for(i in 0 until 5) {
            inputs["$i"] = Nd4j.zeros(2).addi(i)
        }

        val output = listOf("output")
        val createdGraph = createSingleNodeGraph(inputs,"Min",emptyMap(),output,inputs.keys.toList())
        runAssertion(createdGraph,inputs,output)

    }


    @Test
    fun testUnsqueeze() {
        val declarations = OnnxOpDeclarations

        /**
         * Note that this test case is manual due to subtle differences in
         * how onnxruntime and tensorflow appear to interpret their nearest neighbor results.
         * In our test case here, we are verifying against tensorflow-onnx as the implementation.
         *
         */
        val onnxOpRegistry = registry()
        val inputData = Nd4j.linspace(1,15,15).reshape(1,3,1,5)
        val axes = Nd4j.create(floatArrayOf(-2.0f)).castTo(DataType.INT64)
        val input = mapOf("x" to inputData,"axes" to axes)

        val outputs = listOf("y")
        val attributes = emptyMap<String,Any>()
        val inputs = listOf("x","axes")
        val graph = createSingleNodeGraph(input,"Unsqueeze",attributes,outputs,inputs)
        assertEquals(input.size,graph.inputCount)
        assertEquals(1,graph.outputCount)
        val onnxIRGraph = OnnxIRGraph(graph,onnxOpRegistry)
        val onnxGraphRunner = OnnxIRGraphRunner(onnxIRGraph,input.keys.toList(),outputs)
        val assertion = onnxGraphRunner.run(input)
        val importGraph = ImportGraph<Onnx.GraphProto,Onnx.NodeProto,Onnx.NodeProto,Onnx.TensorProto,Onnx.AttributeProto,Onnx.AttributeProto,Onnx.TensorProto.DataType>()

        val importedGraph = importGraph.importGraph(onnxIRGraph,null,null, convertToOnnxTensors(input),onnxOpRegistry)
        val result = importedGraph.output(input,outputs)
        //TODO: add coefficients for better eps comparison, see: https://github.com/eclipse/deeplearning4j/issues/9467
        assertTrue(assertion["y"]!!.equalsWithEps(result["y"],1e-1))
    }



    @Test
    fun testAssign() {
        val declarations = OnnxOpDeclarations

        /**
         * Note that this test case is manual due to subtle differences in
         * how onnxruntime and tensorflow appear to interpret their nearest neighbor results.
         * In our test case here, we are verifying against tensorflow-onnx as the implementation.
         *
         */
        val onnxOpRegistry = registry()
        val startInput = Nd4j.ones(2).castTo(DataType.DOUBLE)
        val inputData = mapOf("x" to startInput,"y" to Nd4j.ones(2).addi(3))
        val outputs = listOf("y")
        val graph = createSingleNodeGraph(inputData,"Assign",emptyMap(),outputs,listOf("x"),Nd4j.ones(2).castTo(DataType.FLOAT))
        runAssertion(graph,inputData,outputs)
    }

    @Test
    fun testCast() {
        val declarations = OnnxOpDeclarations

        /**
         * Note that this test case is manual due to subtle differences in
         * how onnxruntime and tensorflow appear to interpret their nearest neighbor results.
         * In our test case here, we are verifying against tensorflow-onnx as the implementation.
         *
         */
        val onnxOpRegistry = registry()
        val startInput = Nd4j.ones(2).castTo(DataType.DOUBLE)
        val inputData = mapOf("x" to startInput)
        val outputs = listOf("y")
        val attributes = mapOf("to" to Onnx.TensorProto.DataType.FLOAT.ordinal)
        val graph = createSingleNodeGraph(inputData,"Cast",attributes,outputs,listOf("x"),Nd4j.ones(2).castTo(DataType.FLOAT))
        runAssertion(graph,inputData,outputs)
    }

    @Test
    fun testResize() {
        val declarations = OnnxOpDeclarations

        /**
         * Note that this test case is manual due to subtle differences in
         * how onnxruntime and tensorflow appear to interpret their nearest neighbor results.
         * In our test case here, we are verifying against tensorflow-onnx as the implementation.
         *
         */
        val onnxOpRegistry = registry()
        val inputData = Nd4j.linspace(1,16,16).reshape(1,1,4,4)
        val scales = Nd4j.create(floatArrayOf(1.0f,1.0f,0.8f,0.8f))
        val input = mapOf("x" to inputData,"scales" to scales,"roi-empty" to Nd4j.zeros(1,1,1,1))

        val outputs = listOf("y")
        val attributes = mapOf("mode" to "cubic")
        val inputs = listOf("x","roi-empty","scales")
        val graph = createSingleNodeGraph(input,"Resize",attributes,outputs,inputs)
        assertEquals(input.size,graph.inputCount)
        assertEquals(1,graph.outputCount)
        val onnxIRGraph = OnnxIRGraph(graph,onnxOpRegistry)
        val onnxGraphRunner = OnnxIRGraphRunner(onnxIRGraph,input.keys.toList(),outputs)
        val assertion = onnxGraphRunner.run(input)
        val importGraph = ImportGraph<Onnx.GraphProto,Onnx.NodeProto,Onnx.NodeProto,Onnx.TensorProto,Onnx.AttributeProto,Onnx.AttributeProto,Onnx.TensorProto.DataType>()

        val importedGraph = importGraph.importGraph(onnxIRGraph,null,null, convertToOnnxTensors(input),onnxOpRegistry)
        val result = importedGraph.output(input,outputs)
        //TODO: add coefficients for better eps comparison, see: https://github.com/eclipse/deeplearning4j/issues/9467
        assertTrue(assertion["y"]!!.equalsWithEps(result["y"],1e-1))

    }

    fun createSingleNodeGraph(inputs: Map<String,INDArray>,op: String,attributes: Map<String,Any>,outputs: List<String>,inputNames: List<String>,templateTensor: INDArray = inputs.values.first()): Onnx.GraphProto {

        val op = NodeProto {
            inputNames.forEach { t ->
                Input(t)
            }

            name = op.toLowerCase()

            outputs.forEach {
                Output(it)
            }
            attributes.forEach { (t, u) ->
                val attr = AttributeProto {
                    name = t
                }
                val toBuilder = attr.toBuilder()
                when(u.javaClass.name) {
                    "onnx.Onnx\$TensorProto" -> {
                        toBuilder.t = (u as Onnx.TensorProto)
                        toBuilder.type = Onnx.AttributeProto.AttributeType.TENSOR
                    }
                    "java.lang.Double" -> {
                        toBuilder.f = (u as Double).toFloat()
                        toBuilder.type = Onnx.AttributeProto.AttributeType.STRING
                    }
                    "java.lang.Float" -> {
                        toBuilder.f = u as Float
                        toBuilder.type = Onnx.AttributeProto.AttributeType.FLOAT
                    }
                    "java.lang.Integer" -> {
                        toBuilder.i = (u as Integer).toLong()
                        toBuilder.type = Onnx.AttributeProto.AttributeType.INT
                    }
                    "java.lang.Long" -> {
                        toBuilder.i = u as Long
                        toBuilder.type = Onnx.AttributeProto.AttributeType.INT
                    }
                    "java.lang.String" -> {
                        toBuilder.s = byteString(u as String)
                        toBuilder.type = Onnx.AttributeProto.AttributeType.STRING
                    }
                }

                toBuilder.name = t
                Attribute(toBuilder.build())
            }
            opType = op
        }

        val graphRet = GraphProto {
            Node(op)
            name = op.opType.toLowerCase()
            inputs.forEach { (t, u) ->
                if(!t.isEmpty())
                    Input(createValueInfoFromTensor(u,t,false))
            }
            outputs.forEach {
                Output(createValueInfoFromTensor(templateTensor,it,false))
            }


        }

        return graphRet

    }


    @Test
    fun testOpsMapped() {
        val onnxOpRegistry = registry()

        val onnxOpNames = onnxOpRegistry.inputFrameworkOpNames().filter { onnxOpRegistry.registeredOps.containsKey(it) }
        val nd4jOpNames = onnxOpRegistry.nd4jOpNames()
        /**
         * TODO: Assert each op is mapped.
         *
         * Assert all attributes in nd4j are mapped.
         * If not, let's document what isn't and why for each op.
         *
         * Create an op generation tool that allows random generation of test cases
         * based on existing mapped ops between nd4j and tensorflow.
         */
        onnxOpNames.map { onnxOpName -> onnxOpRegistry.lookupOpMappingProcess(onnxOpName)}
            .forEach {
                val onnxNamesMapped = HashSet<String>()
                val nd4jNamesMapped = HashSet<String>()
                //we can ignore dtype for now
                nd4jNamesMapped.add("dtype")
                val opDef = onnxOpRegistry.lookupNd4jOpDef(it.opName())
                val onnxOpDef = onnxOpRegistry.lookupInputFrameworkOpDef(it.inputFrameworkOpName())
                val onnxAssertionNames = HashSet<String>()
                onnxAssertionNames.addAll(onnxOpDef.inputList.map { arg -> arg.toString() })
                onnxAssertionNames.addAll(onnxOpDef.attributeList.map { attr -> attr.name })
                val nd4jOpDefAssertions = HashSet<String>()
                nd4jOpDefAssertions.addAll(opDef.argDescriptorList.map { argDescriptor -> argDescriptor.name })
                val numRequiredInputs = onnxOpDef.inputCount
                val nd4jInputs = opDef.argDescriptorList.filter { arg -> arg.argType == OpNamespace.ArgDescriptor.ArgType.INPUT_TENSOR }.count()
                /**
                 * TODO: Grab total collection of mapped nd4j names
                 * as outputs and mapped onnx names as inputs.
                 * Compare the mapped names to the op definitions
                 * in nd4j and tensorflow respectively.
                 */
                it.tensorMappingRules().forEach { mappingRule ->
                    mappingRule.mappingNamesToPerform().forEach {  mappingName ->
                        onnxNamesMapped.add(mappingName.value)
                        nd4jNamesMapped.add(mappingName.key)
                    }
                }

                it.attributeMappingRules().forEach { mappingRule ->
                    mappingRule.mappingNamesToPerform().forEach { mappingName ->
                        onnxNamesMapped.add(mappingName.value)
                        nd4jNamesMapped.add(mappingName.key)
                    }

                    mappingRule.mappingTransformerArgs().forEach {transformerArg ->
                        run {
                            transformerArg.value.forEach { argValue ->
                                nd4jNamesMapped.add(argValue.name)

                            }
                        }
                    }

                }


                onnxOpDef.inputList.forEach { inputName ->
                    assertTrue(onnxAssertionNames.contains(inputName))
                }

                onnxOpDef.attributeList.map { attrDef -> attrDef.name }.forEach { attrName ->
                    assertTrue(onnxAssertionNames.contains(attrName))
                }



                opDef.argDescriptorList.forEach {  argDef ->
                    //only require it when the

                    when(argDef.argType) {
                        OpNamespace.ArgDescriptor.ArgType.INPUT_TENSOR -> {
                            /**
                             * Nd4j typically has many optional inputs that can also double as attributes
                             * We need to allow for a bit of flexibility in how we handle op definitions. If they're not mapped 1 to 1,
                             * we just log a warning for unmapped inputs. Otherwise we can do an assertion.
                             */
                            if(numRequiredInputs == nd4jInputs)
                                assertTrue(nd4jNamesMapped.contains(argDef.name),"Nd4j op name ${opDef.name} with onnx mapping ${onnxOpDef.name} has missing mapping ${argDef.name}")
                            else if(!nd4jNamesMapped.contains(argDef.name)) {
                                println("Warning: Nd4j op name ${opDef.name} with onnx mapping ${onnxOpDef.name} has missing mapping ${argDef.name}")
                            }
                        }
                        OpNamespace.ArgDescriptor.ArgType.INT32,OpNamespace.ArgDescriptor.ArgType.INT64 -> {
                            assertTrue(nd4jNamesMapped.contains(argDef.name),"Nd4j op name ${opDef.name} with onnx mapping ${onnxOpDef.name}  has missing mapping ${argDef.name}")
                        }
                        OpNamespace.ArgDescriptor.ArgType.DOUBLE, OpNamespace.ArgDescriptor.ArgType.FLOAT -> {
                            assertTrue(nd4jNamesMapped.contains(argDef.name),"Nd4j op name ${opDef.name} with onnx mapping ${onnxOpDef.name}  has missing mapping ${argDef.name}")
                        }
                        OpNamespace.ArgDescriptor.ArgType.BOOL -> {
                            assertTrue(nd4jNamesMapped.contains(argDef.name),"Nd4j op name ${opDef.name} with onnx mapping ${onnxOpDef.name}  has missing mapping ${argDef.name}")
                        }
                    }

                }

            }
    }

    @Test
    fun testOpExecution() {
        val onnxOpRegistry = registry()

        val scalarInputs = mapOf(
            "abs" to -1.0,
            "copy" to 1.0,
            "erfc" to 1.0,
            "exp" to 1.0,
            "identity" to 1.0,
            "neg" to 1.0,
            "ones_as" to 1.0,
            "relu6" to 1.0,
            "round" to 1.0,
            "sign" to 1.0,
            "sin" to 1.0,
            "square" to 1.0,
            "sqrt" to 1.0)

        val scalarFloatOps = mapOf(
            "acos" to 1.0f,
            "asin" to 1.0f,
            "acosh" to 1.0f,
            "asinh" to 1.0f,
            "atan" to 1.0f,
            "atanh" to 0.5f,
            "ceil" to 1.0f,
            "cosh" to 1.0f,
            "cos" to 1.0f,
            "erf" to 1.0f,
            "hard_sigmoid" to 1.0f,
            "floor" to 1.0f,
            "log" to 1.0f,
            "round" to 1.0f,
            "relu" to 1.0f,
            "selu" to 1.0f,
            "sinh" to 1.0f,
            "sigmoid" to 1.0f,
            "softplus" to 1.0f,
            "softsign" to 1.0f,
            "tan" to 1.0f,
            "tanh" to 1.0f
        )


        val singleInputOps = scalarInputs.keys
        val singleInputBooleanOps = mapOf(
            "not" to false
        )

        val singleOutputBooleanOps = mapOf(
            "isfinite" to 1.0f,
            "isinf" to 1.0f,
            "isnan" to 1.0f,
        )

        val pairWiseBooleanOps = mapOf(
            "min" to listOf(1.0,2.0),
            "max" to listOf(1.0,2.0),
            "equals" to listOf(2.0,2.0),
            "greater" to listOf(2.0,1.0),
            "greater_equal" to listOf(2.0,1.0),
            "less" to listOf(2.0,1.0),
            "less_equal" to listOf(2.0,1.0))


        val singleInputIntOutput = mapOf(
            "size" to Nd4j.linspace(1,4,4).reshape(2,2),
            "shape_of" to Nd4j.linspace(1,4,4).reshape(2,2)
        )

        val pairWiseBooleanInputs = mapOf(
            "or" to listOf(true,false),
            "and" to listOf(false,false),
            "xor" to listOf(false,true)
        )


        val singleReduceOps = mapOf(
            "reduce_mean" to Nd4j.linspace(1,4,4).reshape(2,2),
            "reduce_max" to Nd4j.linspace(1,4,4).reshape(2,2),
            "reduce_sum" to Nd4j.linspace(1,4,4).reshape(2,2),
            "reduce_prod" to Nd4j.linspace(1,4,4).reshape(2,2),
            "reduce_norm1" to Nd4j.linspace(1,4,4).reshape(2,2),
            "reduce_norm2" to Nd4j.linspace(1,4,4).reshape(2,2)
            // "reduce_logsumexp" to Nd4j.linspace(1,4,4).reshape(2,2)
        )


        val pairwise = mapOf(
            "add" to listOf(1.0,1.0),
            "subtract" to listOf(2.0,1.0),
            "multiply" to listOf(2.0,1.0),
            "divide" to listOf(2.0,1.0),
            "pow" to listOf(2.0,1.0)
        )

        val mappedOps = setOf("elu","transpose","argmin","argmax","leakyrelu","prelu","flatten_2d")//,"top_k")

        /**
         * NOTE WHEN WRITING TESTS, IF YOU SEE AN ERROR like:
         * java.lang.RuntimeException: Could not find an implementation for the node output:Cos(7)
         *
         * Check the supported data types for each op here:
         * https://github.com/microsoft/onnxruntime/blob/master/docs/OperatorKernels.md
         */

        val importGraph = ImportGraph<Onnx.GraphProto,Onnx.NodeProto,Onnx.NodeProto,Onnx.TensorProto,Onnx.AttributeProto,Onnx.AttributeProto,Onnx.TensorProto.DataType>()
        val finishedOps = HashSet<String>()
        onnxOpRegistry.mappingProcessNames()
            .filter { onnxOpRegistry.hasMappingOpProcess(it) }
            .map { onnxOpRegistry.lookupOpMappingProcess(it) }.forEach { mappingProcess ->
                val nd4jOpDef = onnxOpRegistry.lookupNd4jOpDef(mappingProcess.opName())
                val onnxOpDef = onnxOpRegistry.lookupInputFrameworkOpDef(mappingProcess.inputFrameworkOpName())
                if(scalarInputs.containsKey(nd4jOpDef.name)) {
                    print("Running op $nd4jOpDef.name")
                    val input = Nd4j.scalar(scalarInputs[mappingProcess.opName()]).castTo(org.nd4j.linalg.api.buffer.DataType.DOUBLE)
                    val graphToRun = GraphProto {
                        Input(createValueInfoFromTensor(input,"input"))
                        //Initializer(convertedTensor)
                        Node(NodeProto {
                            name = "output"
                            opType = onnxOpDef.opType
                            Input("input")
                            Output("output")

                        })

                        Output(createValueInfoFromTensor(input,"output"))
                    }


                    val onnxIRGraph = OnnxIRGraph(graphToRun,onnxOpRegistry)
                    val onnxGraphRunner = OnnxIRGraphRunner(onnxIRGraph,listOf("input"),listOf("output"))
                    val importedGraph = importGraph.importGraph(onnxIRGraph,null,null,HashMap(),onnxOpRegistry)
                    val inputs = mapOf("input" to input)
                    val assertion = onnxGraphRunner.run(inputs)
                    val result = importedGraph.output(inputs,"output")
                    assertEquals(assertion["output"]!!.reshape(1,1),result["output"]!!.reshape(1,1),"Function ${nd4jOpDef.name} failed with input $input")
                    finishedOps.add(nd4jOpDef.name)

                } else if(scalarFloatOps.containsKey(nd4jOpDef.name)) {
                    print("Running op $nd4jOpDef.name")
                    val input = Nd4j.scalar(scalarFloatOps[mappingProcess.opName()]).castTo(org.nd4j.linalg.api.buffer.DataType.FLOAT)

                    val graphToRun = GraphProto {
                        Input(createValueInfoFromTensor(input,"input"))
                        //Initializer(convertedTensor)
                        Node(NodeProto {
                            name = "output"
                            opType = onnxOpDef.opType
                            Input("input")
                            Output("output")

                        })

                        Output(createValueInfoFromTensor(input,"output"))
                    }


                    val onnxIRGraph = OnnxIRGraph(graphToRun,onnxOpRegistry)
                    val onnxGraphRunner = OnnxIRGraphRunner(onnxIRGraph,listOf("input"),listOf("output"))
                    val importedGraph = importGraph.importGraph(onnxIRGraph,null,null,HashMap(),onnxOpRegistry)
                    val inputs = mapOf("input" to input)
                    val assertion = onnxGraphRunner.run(inputs)
                    val result = importedGraph.output(inputs,"output")
                    assertEquals(assertion["output"]!!.reshape(1,1),result["output"]!!.reshape(1,1),"Function ${nd4jOpDef.name} failed with input $input")
                    finishedOps.add(nd4jOpDef.name)

                }

                else if(singleOutputBooleanOps.containsKey(nd4jOpDef.name)) {
                    print("Running op $nd4jOpDef.name")
                    val input = Nd4j.scalar(singleOutputBooleanOps[mappingProcess.opName()]).castTo(org.nd4j.linalg.api.buffer.DataType.FLOAT)
                    val convertedTensor = convertToOnnxTensor(input,"input")
                    val convertedOutputTensor = convertToOnnxTensor(input,"output")

                    val graphToRun = GraphProto {
                        Input(createValueInfoFromTensor(input,"input"))
                        //Initializer(convertedTensor)
                        Node(NodeProto {
                            name = "output"
                            opType = onnxOpDef.opType
                            Input("input")
                            Output("output")

                        })

                        Output(createValueInfoFromTensor(Nd4j.create(booleanArrayOf(true)).reshape(),"output"))
                    }


                    val onnxIRGraph = OnnxIRGraph(graphToRun,onnxOpRegistry)
                    val onnxGraphRunner = OnnxIRGraphRunner(onnxIRGraph,listOf("input"),listOf("output"))
                    val importedGraph = importGraph.importGraph(onnxIRGraph,null,null,HashMap(),onnxOpRegistry)
                    val inputs = mapOf("input" to input)
                    val assertion = onnxGraphRunner.run(inputs)
                    val result = importedGraph.output(inputs,"output")
                    assertEquals(assertion["output"]!!.reshape(1,1),result["output"]!!.reshape(1,1),"Function ${nd4jOpDef.name} failed with input $input")
                    finishedOps.add(nd4jOpDef.name)

                }


                else if(pairwise.containsKey(nd4jOpDef.name)) {
                    print("Running op def $nd4jOpDef.name")
                    val x = Nd4j.scalar(pairwise[mappingProcess.opName()]!![0]!!).castTo(org.nd4j.linalg.api.buffer.DataType.DOUBLE)
                    val y = Nd4j.scalar(pairwise[mappingProcess.opName()]!![1]!!).castTo(org.nd4j.linalg.api.buffer.DataType.DOUBLE)

                    val graphToRun = GraphProto {
                        Input(createValueInfoFromTensor(x,"x"))
                        Input(createValueInfoFromTensor(y,"y"))
                        //Initializer(convertedTensor)
                        Node(NodeProto {
                            name = "output"
                            opType = onnxOpDef.opType
                            Input("x")
                            Input("y")
                            Output("output")

                        })

                        Output(createValueInfoFromTensor(x,"output"))
                    }


                    val onnxIRGraph = OnnxIRGraph(graphToRun,onnxOpRegistry)
                    val onnxGraphRunner = OnnxIRGraphRunner(onnxIRGraph,listOf("x","y"),listOf("output"))
                    val importedGraph = importGraph.importGraph(onnxIRGraph,null,null,
                        hashMapOf("x" to convertToOnnxTensor(x,"x"),"y" to convertToOnnxTensor(y,"y")),onnxOpRegistry)
                    val inputs = mapOf("x" to x,"y" to y)
                    val result = importedGraph.output(inputs,"output")
                    val assertion = onnxGraphRunner.run(inputs)
                    assertEquals(assertion["output"]!!.getDouble(0),result["output"]!!.getDouble(0),"Function ${nd4jOpDef.name} failed with input $x $y")
                    finishedOps.add(nd4jOpDef.name)

                }  else if(pairWiseBooleanInputs.containsKey(nd4jOpDef.name)) {
                    print("Running op def $nd4jOpDef.name")
                    val x = Nd4j.scalar(pairWiseBooleanInputs[mappingProcess.opName()]!![0]!!).castTo(org.nd4j.linalg.api.buffer.DataType.BOOL)
                    val y = Nd4j.scalar(pairWiseBooleanInputs[mappingProcess.opName()]!![1]!!).castTo(org.nd4j.linalg.api.buffer.DataType.BOOL)

                    val graphToRun = GraphProto {
                        Input(createValueInfoFromTensor(x,"x"))
                        Input(createValueInfoFromTensor(y,"y"))
                        Node(NodeProto {
                            name = "output"
                            opType = onnxOpDef.opType
                            Input("x")
                            Input("y")
                            Output("output")

                        })

                        Output(createValueInfoFromTensor(x,"output"))
                    }


                    val onnxIRGraph = OnnxIRGraph(graphToRun,onnxOpRegistry)
                    val onnxGraphRunner = OnnxIRGraphRunner(onnxIRGraph,listOf("x","y"),listOf("output"))
                    val importedGraph = importGraph.importGraph(onnxIRGraph,null,null,
                        hashMapOf("x" to convertToOnnxTensor(x,"x"),"y" to convertToOnnxTensor(y,"y")),onnxOpRegistry)
                    val inputs = mapOf("x" to x,"y" to y)
                    val assertion = onnxGraphRunner.run(inputs)
                    val result = importedGraph.output(inputs,"output")
                    assertEquals(assertion["output"]!!.getDouble(0),result["output"]!!.getDouble(0),"Function ${nd4jOpDef.name} failed with input $x $y")
                    finishedOps.add(nd4jOpDef.name)

                } else if(pairWiseBooleanOps.containsKey(nd4jOpDef.name)) {
                    print("Running op def $nd4jOpDef.name")
                    val x = Nd4j.scalar(pairWiseBooleanOps[mappingProcess.opName()]!![0]!!).castTo(org.nd4j.linalg.api.buffer.DataType.FLOAT)
                    val y = Nd4j.scalar(pairWiseBooleanOps[mappingProcess.opName()]!![1]!!).castTo(org.nd4j.linalg.api.buffer.DataType.FLOAT)
                    val output = Nd4j.scalar(pairWiseBooleanOps[mappingProcess.opName()]!![1]!!).castTo(org.nd4j.linalg.api.buffer.DataType.BOOL)

                    val graphToRun = GraphProto {
                        Input(createValueInfoFromTensor(x,"x"))
                        Input(createValueInfoFromTensor(y,"y"))
                        //Initializer(convertedTensor)
                        Node(NodeProto {
                            name = "output"
                            opType = onnxOpDef.opType
                            Input("x")
                            Input("y")
                            Output("output")

                        })

                        Output(createValueInfoFromTensor(output,"output"))
                    }


                    val onnxIRGraph = OnnxIRGraph(graphToRun,onnxOpRegistry)
                    val onnxGraphRunner = OnnxIRGraphRunner(onnxIRGraph,listOf("x","y"),listOf("output"))
                    val importedGraph = importGraph.importGraph(onnxIRGraph,null,null,
                        hashMapOf("x" to convertToOnnxTensor(x,"x"),"y" to convertToOnnxTensor(y,"y")),onnxOpRegistry)
                    val inputs = mapOf("x" to x,"y" to y)
                    val assertion = onnxGraphRunner.run(inputs)
                    val result = importedGraph.output(inputs,"output")
                    assertEquals(assertion["output"]!!.getDouble(0),result["output"]!!.getDouble(0),"Function ${nd4jOpDef.name} failed with input $x $y")
                    finishedOps.add(nd4jOpDef.name)

                }

                else if(singleInputBooleanOps.containsKey(nd4jOpDef.name)) {
                    print("Running op def $nd4jOpDef.name")
                    val x = Nd4j.create(booleanArrayOf(singleInputBooleanOps[mappingProcess.opName()]!!)).castTo(org.nd4j.linalg.api.buffer.DataType.BOOL)
                    val output = Nd4j.create(booleanArrayOf(singleInputBooleanOps[mappingProcess.opName()]!!)).castTo(org.nd4j.linalg.api.buffer.DataType.BOOL)

                    val graphToRun = GraphProto {
                        Input(createValueInfoFromTensor(x,"x"))
                        //Initializer(convertedTensor)
                        Node(NodeProto {
                            name = "output"
                            opType = onnxOpDef.opType
                            Input("x")
                            Output("output")

                        })

                        Output(createValueInfoFromTensor(output,"output"))
                    }


                    val onnxIRGraph = OnnxIRGraph(graphToRun,onnxOpRegistry)
                    val onnxGraphRunner = OnnxIRGraphRunner(onnxIRGraph,listOf("x"),listOf("output"))
                    val importedGraph = importGraph.importGraph(onnxIRGraph,null,null,hashMapOf("x" to convertToOnnxTensor(x,"x")),onnxOpRegistry)
                    val inputs = mapOf("x" to x)
                    val assertion = onnxGraphRunner.run(inputs)
                    val result = importedGraph.output(inputs,"output")
                    finishedOps.add(nd4jOpDef.name)

                    //assertEquals("Function ${nd4jOpDef.name} failed with input $x",assertion["output"]!!.reshape(1,1),result["output"]!!.reshape(1,1))
                }

                else if(singleReduceOps.containsKey(nd4jOpDef.name)) {
                    print("Running op def $nd4jOpDef.name")
                    val x = singleReduceOps[mappingProcess.opName()]!!.castTo(org.nd4j.linalg.api.buffer.DataType.FLOAT)
                    val output = x.mean(0).reshape(2)
                        .castTo(org.nd4j.linalg.api.buffer.DataType.FLOAT)


                    val graphToRun = GraphProto {
                        Input(createValueInfoFromTensor(x,"x"))
                        //Initializer(convertedTensor)
                        Node(NodeProto {
                            name = "output"
                            opType = onnxOpDef.opType
                            Input("x")
                            Output("output")
                            Attribute(Onnx.AttributeProto.newBuilder()
                                .setType(Onnx.AttributeProto.AttributeType.INTS)
                                .setName("axes").addInts(0).build())
                            Attribute(Onnx.AttributeProto.newBuilder()
                                .setType(Onnx.AttributeProto.AttributeType.INT)
                                .setI(0)
                                .setName("keepdims").build())

                        })

                        Output(createValueInfoFromTensor(output,"output"))
                    }


                    val onnxIRGraph = OnnxIRGraph(graphToRun,onnxOpRegistry)
                    val inputs = mapOf("x" to x)
                    val importedGraph = importGraph.importGraph(onnxIRGraph,null,null,hashMapOf("x" to convertToOnnxTensor(x,"x")),onnxOpRegistry)
                    val result = importedGraph.output(inputs,"output")
                    val onnxGraphRunner = OnnxIRGraphRunner(onnxIRGraph,listOf("x"),listOf("output"))
                    val assertion = onnxGraphRunner.run(inputs)
                    assertEquals(assertion["output"]!!.reshape(1,2),result["output"]!!.reshape(1,2),"Function ${nd4jOpDef.name} failed with input $x")
                    finishedOps.add(nd4jOpDef.name)

                } else if(mappedOps.contains(nd4jOpDef.name)){
                    val graphForOp = graphForOp(nd4jOpDef.name)
                    graphForOp.forEach { graph ->
                        val onnxIRGraph = OnnxIRGraph(graph.graphDef,onnxOpRegistry)
                        val inputs =graph.inputArrays
                        val convertedArrays = HashMap<String,Onnx.TensorProto>()
                        graph.inputArrays.forEach { name, arr ->
                            convertedArrays[name] = convertToOnnxTensor(arr,name)
                        }
                        val importedGraph = importGraph.importGraph(onnxIRGraph,null,null,convertedArrays,onnxOpRegistry)
                        val onnxGraphRunner = OnnxIRGraphRunner(onnxIRGraph,graph.inputNames,graph.outputNames)
                        val assertion = onnxGraphRunner.run(inputs)
                        val result = importedGraph.output(inputs,graph.outputNames)
                        assertEquals(assertion.keys,result.keys)
                        result.forEach { name,arr ->
                            if(arr.length().toInt() == 1) {
                                assertEquals(assertion[name]!!.getDouble(0),arr.getDouble(0),1e-3,"Function ${nd4jOpDef.name} failed with input ${graph.inputNames}")
                            }
                            else {
                                assertEquals(assertion[name],arr,"Function ${nd4jOpDef.name} failed with input ${graph.inputNames}")
                            }
                        }

                        finishedOps.add(nd4jOpDef.name)


                    }


                } else   if(singleInputIntOutput.containsKey(nd4jOpDef.name)) {
                    print("Running op $nd4jOpDef.name")
                    val input = singleInputIntOutput[mappingProcess.opName()]!!.castTo(org.nd4j.linalg.api.buffer.DataType.INT64)
                    val graphToRun = GraphProto {
                        Input(createValueInfoFromTensor(input,"input"))
                        //Initializer(convertedTensor)
                        Node(NodeProto {
                            name = "output"
                            opType = onnxOpDef.opType
                            Input("input")
                            Output("output")

                        })

                        Output(createValueInfoFromTensor(input,"output",false ))
                    }


                    val onnxIRGraph = OnnxIRGraph(graphToRun,onnxOpRegistry)
                    val onnxGraphRunner = OnnxIRGraphRunner(onnxIRGraph,listOf("input"),listOf("output"))
                    val importedGraph = importGraph.importGraph(onnxIRGraph,null,null,HashMap(),onnxOpRegistry)
                    val inputs = mapOf("input" to input)
                    val assertion = onnxGraphRunner.run(inputs)
                    val result = importedGraph.output(inputs,"output")
                    if(assertion["output"]!!.length() == 1L)
                        assertEquals(assertion["output"]!!.reshape(1,1),result["output"]!!.reshape(1,1),"Function ${nd4jOpDef.name} failed with input $input")
                    else
                        assertEquals(assertion["output"]!!.ravel(),result["output"]!!.ravel(),"Function ${nd4jOpDef.name} failed with input $input")
                    finishedOps.add(nd4jOpDef.name)

                }
            }

        println("Finished ops totaling ${finishedOps.size} out of ${onnxOpRegistry.mappedNd4jOpNames().size}")
    }



    fun graphForOp(opName: String): List<OnnxGraphInput> {
        when(opName) {
            "non_max_suppression_v3" -> {
                /**
                 * TODO: Add pre and post processing for each node.
                 * Our NMS requires 2d, but onnx is 3d. Attempt to see
                 * if generalized pre/post processing node additions as part of a mapping process can work.
                 *
                 */
                print("Running op def $opName")
                val boxesVal = Nd4j.create(arrayOf(
                    floatArrayOf(0f,0f,1f,1f),
                    floatArrayOf(0f,0.1f,1f,1.1f),
                    floatArrayOf(0f,-0.1f,1f,0.9f),
                    floatArrayOf(0f,10f,1f,11f)
                )).reshape(1,4,4).castTo(org.nd4j.linalg.api.buffer.DataType.FLOAT)

                val scoresVal = Nd4j.create(listOf(0.9f,0.75f,0.6f,0.95f).toFloatArray())
                    .reshape(1,1,4)
                    .castTo(org.nd4j.linalg.api.buffer.DataType.FLOAT)
                val maxOutputSize = Nd4j.scalar(4.0).castTo(DataType.INT64)
                val iouThreshold = Nd4j.scalar(0.5).castTo(DataType.FLOAT)
                val scoreThreshold = Nd4j.scalar(0.0).castTo(DataType.FLOAT)

                val inputs = mapOf("boxes" to boxesVal,"scores" to scoresVal,"max_output_boxes_per_class" to maxOutputSize,
                    "iou_threshold" to iouThreshold,"score_threshold" to scoreThreshold)
                val output = Nd4j.scalar(1)
                    .castTo(org.nd4j.linalg.api.buffer.DataType.INT64)


                val graphToRun = GraphProto {
                    Input(createValueInfoFromTensor(boxesVal,"boxes",false))
                    Input(createValueInfoFromTensor(scoresVal,"scores",false))
                    Input(createValueInfoFromTensor(maxOutputSize,"max_output_boxes_per_class",false))
                    Input(createValueInfoFromTensor(iouThreshold,"iou_threshold",false))
                    Input(createValueInfoFromTensor(scoreThreshold,"score_threshold",false))

                    //Initializer(convertedTensor)
                    Node(NodeProto {
                        Input("boxes")
                        Input("scores")
                        Input("max_output_boxes_per_class")
                        Input("iou_threshold")
                        Input("score_threshold")
                        Output("output")
                        name = "output"
                        opType = "NonMaxSuppression"



                    })

                    Output(createValueInfoFromTensor(output,"output",false))
                }

                return listOf(OnnxGraphInput(graphToRun,listOf("boxes","scores","max_output_boxes_per_class","iou_threshold","score_threshold"),listOf("output"),inputs,inputs))
            }
            "argmin","argmax" -> {
                print("Running op def $opName")
                val x = Nd4j.linspace(1,4,4).reshape(2,2).castTo(org.nd4j.linalg.api.buffer.DataType.FLOAT)
                val output = x.mean(0).reshape(2)
                    .castTo(org.nd4j.linalg.api.buffer.DataType.INT64)


                val graphToRun = GraphProto {
                    Input(createValueInfoFromTensor(x,"x"))
                    //Initializer(convertedTensor)
                    Node(NodeProto {
                        name = "output"
                        opType = if(opName == "argmin") "ArgMin" else "ArgMax"
                        Input("x")
                        Output("output")
                        Attribute(Onnx.AttributeProto.newBuilder()
                            .setType(Onnx.AttributeProto.AttributeType.INT)
                            .setName("axis").setI(0).build())
                        Attribute(Onnx.AttributeProto.newBuilder()
                            .setType(Onnx.AttributeProto.AttributeType.INT)
                            .setI(0)
                            .setName("keepdims").build())
                        Attribute(Onnx.AttributeProto.newBuilder()
                            .setType(Onnx.AttributeProto.AttributeType.INT)
                            .setI(1)
                            .setName("select_last_index").build())

                    })

                    Output(createValueInfoFromTensor(output,"output",false))
                }

                val inputMap = mapOf("x" to x)
                return listOf(OnnxGraphInput(graphToRun,listOf("x"),listOf("output"),inputMap,inputMap))
            }
            "top_k" -> {
                val input = Nd4j.linspace(1,4,4).reshape(2,2).castTo(org.nd4j.linalg.api.buffer.DataType.FLOAT)
                val k = Nd4j.scalar(2.0).castTo(DataType.INT64).reshape(1)
                val output = Nd4j.linspace(1,4,4).reshape(2,2).castTo(org.nd4j.linalg.api.buffer.DataType.INT64)

                val graphToRun = GraphProto {
                    Input(createValueInfoFromTensor(input,"input"))
                    Input(createValueInfoFromTensor(k,"k"))
                    Node(NodeProto {
                        name = "output"
                        opType = "TopK"
                        Input("input")
                        Input("k")
                        Output("output")
                        Output("indices")
                        Attribute(Onnx.AttributeProto.newBuilder()
                            .setType(Onnx.AttributeProto.AttributeType.INT)
                            .setI(0)
                            .setName("axis").build())
                        Attribute(Onnx.AttributeProto.newBuilder()
                            .setType(Onnx.AttributeProto.AttributeType.INT)
                            .setI(1)
                            .setName("sorted").build())
                        Attribute(Onnx.AttributeProto.newBuilder()
                            .setType(Onnx.AttributeProto.AttributeType.INT)
                            .setI(1)
                            .setName("largest").build())

                    })


                    Output(createValueInfoFromTensor(input,"output",false))
                    Output(createValueInfoFromTensor(output,"indices",false))
                }

                val inputMap = mapOf("input" to input,"k" to k)
                return listOf(OnnxGraphInput(graphToRun,listOf("input","k"),listOf("output","indices"),inputMap,inputMap))

            }
            "transpose" -> {
                val input = Nd4j.linspace(1,6,6).reshape(3,2).castTo(org.nd4j.linalg.api.buffer.DataType.FLOAT)
                val output = Nd4j.linspace(1,6,6).reshape(2,3).castTo(org.nd4j.linalg.api.buffer.DataType.FLOAT)

                val graphToRun = GraphProto {
                    Input(createValueInfoFromTensor(input,"input"))
                    //Initializer(convertedTensor)
                    Node(NodeProto {
                        name = "output"
                        opType = "Transpose"
                        Input("input")
                        Output("output")
                        Attribute(Onnx.AttributeProto.newBuilder()
                            .setType(Onnx.AttributeProto.AttributeType.INTS)
                            .addInts(1).addInts(0)
                            .setName("perm").build())

                    })

                    Output(createValueInfoFromTensor(output,"output"))
                }

                val inputMap = mapOf("input" to input)
                return listOf(OnnxGraphInput(graphToRun,listOf("input"),listOf("output"),inputMap,inputMap))

            }
            "prelu" -> {
                val input = Nd4j.randn(3,4,5).castTo(org.nd4j.linalg.api.buffer.DataType.FLOAT)
                val alpha = Nd4j.zeros(1,1,5).addi(0.1).castTo(DataType.FLOAT)
                val graphToRun = GraphProto {
                    Input(createValueInfoFromTensor(input,"input",false))
                    Input(createValueInfoFromTensor(input,"slope",false))
                    //Initializer(convertedTensor)
                    Node(NodeProto {
                        name = "output"
                        opType = "PRelu"
                        Input("input")
                        Input("slope")
                        Output("output")

                    })

                    Output(createValueInfoFromTensor(input,"output",false))
                }

                val inputMap = mapOf("input" to input,"slope" to alpha)
                return listOf(OnnxGraphInput(graphToRun,listOf("input","slope"),listOf("output"),inputMap,inputMap))

            }
            "elu","leakyrelu" -> {
                val input = Nd4j.scalar(1.0f).castTo(org.nd4j.linalg.api.buffer.DataType.FLOAT)
                val graphToRun = GraphProto {
                    Input(createValueInfoFromTensor(input,"input"))
                    //Initializer(convertedTensor)
                    Node(NodeProto {
                        name = "output"
                        opType = if(name == "elu") "Elu" else "LeakyRelu"
                        Input("input")
                        Output("output")
                        Attribute(Onnx.AttributeProto.newBuilder()
                            .setType(Onnx.AttributeProto.AttributeType.FLOAT)
                            .setF(1.0f)
                            .setName("alpha").build())

                    })

                    Output(createValueInfoFromTensor(input,"output"))
                }

                val inputMap = mapOf("input" to input)
                return listOf(OnnxGraphInput(graphToRun,listOf("input"),listOf("output"),inputMap,inputMap))

            }

            "flatten_2d" -> {
                val x = Nd4j.randn(2,3,4).castTo(org.nd4j.linalg.api.buffer.DataType.DOUBLE)

                val graphToRun = GraphProto {
                    Input(createValueInfoFromTensor(x,"x"))
                    //Initializer(convertedTensor)
                    Node(NodeProto {
                        name = "output"
                        opType = "Flatten"
                        Input("x")
                        Output("output")
                        Attribute(Onnx.AttributeProto.newBuilder()
                            .setType(Onnx.AttributeProto.AttributeType.INT)
                            .setI(1)
                            .setName("axis").build())
                    })

                    Output(createValueInfoFromTensor(x,"output",false))
                }

                val inputMap = mapOf("x" to x)
                return listOf(OnnxGraphInput(graphToRun,listOf("x"),listOf("output"),inputMap,inputMap))


            }

            "mod" -> {
                val x = Nd4j.scalar(2.0).castTo(org.nd4j.linalg.api.buffer.DataType.DOUBLE)
                val y = Nd4j.scalar(2.0).castTo(org.nd4j.linalg.api.buffer.DataType.DOUBLE)

                val graphToRun = GraphProto {
                    Input(createValueInfoFromTensor(x,"x"))
                    Input(createValueInfoFromTensor(y,"y"))
                    //Initializer(convertedTensor)
                    Node(NodeProto {
                        name = "output"
                        opType = "Mod"
                        Input("x")
                        Input("y")
                        Output("output")
                        Attribute(Onnx.AttributeProto.newBuilder()
                            .setType(Onnx.AttributeProto.AttributeType.INT)
                            .setI(1)
                            .setName("fmod").build())
                    })

                    Output(createValueInfoFromTensor(x,"output"))
                }

                val inputMap = mapOf("x" to x,"y" to y)
                return listOf(OnnxGraphInput(graphToRun,listOf("x","y"),listOf("output"),inputMap,inputMap))


            }
            else -> {
                throw IllegalArgumentException("Illegal op name $opName")
            }

        }
    }

}