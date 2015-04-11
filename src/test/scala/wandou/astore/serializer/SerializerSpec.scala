package wandou.astore.serializer

import akka.actor.ActorSystem
import akka.testkit.{ ImplicitSender, TestKit }
import akka.serialization.SerializationExtension
import com.typesafe.config.ConfigFactory
import java.util.concurrent.TimeUnit
import org.apache.avro.Schema.Parser
import org.apache.avro.generic.GenericData
import org.apache.avro.util.Utf8
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }
import scala.collection.immutable
import scala.concurrent.duration.FiniteDuration
import wandou.astore.AddSchema
import wandou.astore.UpdatedFields
import wandou.avro.RecordBuilder

object SerializerSpec {
  val config = ConfigFactory.parseString("""

akka.actor {
  serializers {
    avro = "wandou.astore.serializer.AvroSerializer"
    schema = "wandou.astore.serializer.SchemaSerializer"
    record-event= "wandou.astore.serializer.RecordEventSerializer"
    addschema-event= "wandou.astore.serializer.AddSchemaEventSerializer"
    writemessages = "akka.persistence.serialization.WriteMessagesSerializer"
  }
  serialization-bindings {
    "org.apache.avro.generic.GenericContainer" = avro
    "org.apache.avro.Schema" = schema
    "wandou.astore.package$UpdatedFields" = record-event
    "wandou.astore.package$AddSchema" = addschema-event
    "akka.persistence.journal.AsyncWriteTarget$WriteMessages" = writemessages
  }

  provider = "akka.cluster.ClusterActorRefProvider"
}

akka.remote.netty.tcp.hostname = "127.0.0.1"
akka.remote.netty.tcp.port = 2550

""")

  private val classLoader = this.getClass.getClassLoader

  val schema = new Parser().parse(classLoader.getResourceAsStream("avsc/PersonInfo.avsc"))
  val recordBuilder = RecordBuilder(schema)
  val emails = {
    val xs = new GenericData.Array[Utf8](0, schema.getField("emails").schema)
    // Utf8 with same string is not equils to String, we use Utf8 for test spec 
    xs.add(new Utf8("abc@abc.com"))
    xs.add(new Utf8("def@abc.com"))
    xs
  }
  val record = {
    val rec = recordBuilder.build()
    rec.put("emails", emails)
    rec
  }
}

class SerializerSpec(_system: ActorSystem) extends TestKit(_system) with ImplicitSender with WordSpecLike with Matchers with BeforeAndAfterAll {
  import SerializerSpec._

  def this() = this(ActorSystem("MySpec", SerializerSpec.config))

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  val serialization = SerializationExtension(system)
  def test(obj: AnyRef) = {
    val serializer = serialization.findSerializerFor(obj)
    val bytes = serializer.toBinary(obj)
    val res = serialization.deserialize(bytes, obj.getClass).get
    assertResult(obj)(res)

    val resById = serialization.deserialize(bytes, serializer.identifier, Some(obj.getClass)).get
    assertResult(obj)(resById)
  }

  "Serializer" must {
    "handle Avro record" in {
      test(record)
    }

    "handle Avro array" in {
      test(emails)
    }

    "handle Avro schema" in {
      test(schema)
    }

    "handle AddSchema" in {
      val duration = FiniteDuration(100L, TimeUnit.SECONDS)
      val addSchema = AddSchema("entityName", schema, duration)
      test(addSchema)
    }

    "handle UpdatedFields" in {
      val obj = UpdatedFields(List(
        (0, "James Bond"),
        (1, 30),
        (3, emails)))
      test(obj)
    }
  }
}
