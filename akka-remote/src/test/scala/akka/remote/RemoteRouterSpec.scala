/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import akka.testkit._
import akka.routing._
import akka.actor._
import akka.remote.routing._
import com.typesafe.config._

object RemoteRouterSpec {
  class Echo extends Actor {
    def receive = {
      case _ ⇒ sender ! self
    }
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class RemoteRouterSpec extends AkkaSpec("""
akka {
  actor.provider = "akka.remote.RemoteActorRefProvider"
  remote.netty.tcp {
    hostname = localhost
    port = 0
    server-socket-worker-pool.pool-size-max = 2
    client-socket-worker-pool.pool-size-max = 2
  }
  actor.deployment {
    /blub {
      router = round-robin
      nr-of-instances = 2
      target.nodes = ["akka.tcp://remote-sys@localhost:12347"]
    }
    /elastic-blub {
      router = round-robin
      resizer {
        lower-bound = 2
        upper-bound = 3
      }
      target.nodes = ["akka.tcp://remote-sys@localhost:12347"]
    }
    /remote-blub {
      remote = "akka.tcp://remote-sys@localhost:12347"
      router = round-robin
      nr-of-instances = 2
    }
    /local-blub {
      remote = "akka://RemoteRouterSpec"
      router = round-robin
      nr-of-instances = 2
      target.nodes = ["akka.tcp://remote-sys@localhost:12347"]
    }
    /local-blub2 {
      router = round-robin
      nr-of-instances = 4
      target.nodes = ["akka.tcp://remote-sys@localhost:12347"]
    }
  }
}
""") with ImplicitSender {

  import RemoteRouterSpec._

  val conf = ConfigFactory.parseString("""akka.remote.netty.tcp.port=12347
akka.actor.deployment {
  /remote-override {
    router = round-robin
    nr-of-instances = 4
  }
}""").withFallback(system.settings.config)
  val otherSystem = ActorSystem("remote-sys", conf)

  override def afterTermination() {
    shutdown(otherSystem)
  }

  "A Remote Router" must {

    "deploy its children on remote host driven by configuration" in {
      val router = system.actorOf(Props[Echo].withRouter(RoundRobinRouter(2)), "blub")
      val replies = for (i ← 1 to 5) yield {
        router ! ""
        expectMsgType[ActorRef].path
      }
      val children = replies.toSet
      children must have size 2
      children.map(_.parent) must have size 1
      children foreach (_.address.toString must be === "akka.tcp://remote-sys@localhost:12347")
      system.stop(router)
    }

    "deploy its children on remote host driven by programatic definition" in {
      val router = system.actorOf(Props[Echo].withRouter(new RemoteRouterConfig(RoundRobinRouter(2),
        Seq(Address("akka.tcp", "remote-sys", "localhost", 12347)))), "blub2")
      val replies = for (i ← 1 to 5) yield {
        router ! ""
        expectMsgType[ActorRef].path
      }
      val children = replies.toSet
      children must have size 2
      children.map(_.parent) must have size 1
      children foreach (_.address.toString must be === "akka.tcp://remote-sys@localhost:12347")
      system.stop(router)
    }

    "deploy dynamic resizable number of children on remote host driven by configuration" in {
      val router = system.actorOf(Props[Echo].withRouter(FromConfig), "elastic-blub")
      val replies = for (i ← 1 to 5000) yield {
        router ! ""
        expectMsgType[ActorRef].path
      }
      val children = replies.toSet
      children.size must be >= 2
      children.map(_.parent) must have size 1
      children foreach (_.address.toString must be === "akka.tcp://remote-sys@localhost:12347")
      system.stop(router)
    }

    "deploy remote routers based on configuration" in {
      val router = system.actorOf(Props[Echo].withRouter(FromConfig), "remote-blub")
      router.path.address.toString must be("akka.tcp://remote-sys@localhost:12347")
      val replies = for (i ← 1 to 5) yield {
        router ! ""
        expectMsgType[ActorRef].path
      }
      val children = replies.toSet
      children must have size 2
      val parents = children.map(_.parent)
      parents must have size 1
      parents.head must be(router.path)
      children foreach (_.address.toString must be === "akka.tcp://remote-sys@localhost:12347")
      system.stop(router)
    }

    "deploy remote routers based on explicit deployment" in {
      val router = system.actorOf(Props[Echo].withRouter(RoundRobinRouter(2))
        .withDeploy(Deploy(scope = RemoteScope(AddressFromURIString("akka.tcp://remote-sys@localhost:12347")))), "remote-blub2")
      router.path.address.toString must be("akka.tcp://remote-sys@localhost:12347")
      val replies = for (i ← 1 to 5) yield {
        router ! ""
        expectMsgType[ActorRef].path
      }
      val children = replies.toSet
      children must have size 2
      val parents = children.map(_.parent)
      parents must have size 1
      parents.head must be(router.path)
      children foreach (_.address.toString must be === "akka.tcp://remote-sys@localhost:12347")
      system.stop(router)
    }

    "let remote deployment be overridden by local configuration" in {
      val router = system.actorOf(Props[Echo].withRouter(RoundRobinRouter(2))
        .withDeploy(Deploy(scope = RemoteScope(AddressFromURIString("akka.tcp://remote-sys@localhost:12347")))), "local-blub")
      router.path.address.toString must be("akka://RemoteRouterSpec")
      val replies = for (i ← 1 to 5) yield {
        router ! ""
        expectMsgType[ActorRef].path
      }
      val children = replies.toSet
      children must have size 2
      val parents = children.map(_.parent)
      parents must have size 1
      parents.head.address must be(Address("akka.tcp", "remote-sys", "localhost", 12347))
      children foreach (_.address.toString must be === "akka.tcp://remote-sys@localhost:12347")
      system.stop(router)
    }

    "let remote deployment router be overridden by local configuration" in {
      val router = system.actorOf(Props[Echo].withRouter(RoundRobinRouter(2))
        .withDeploy(Deploy(scope = RemoteScope(AddressFromURIString("akka.tcp://remote-sys@localhost:12347")))), "local-blub2")
      router.path.address.toString must be("akka.tcp://remote-sys@localhost:12347")
      val replies = for (i ← 1 to 5) yield {
        router ! ""
        expectMsgType[ActorRef].path
      }
      val children = replies.toSet
      children must have size 4
      val parents = children.map(_.parent)
      parents must have size 1
      parents.head must be(router.path)
      children foreach (_.address.toString must be === "akka.tcp://remote-sys@localhost:12347")
      system.stop(router)
    }

    "let remote deployment be overridden by remote configuration" in {
      val router = system.actorOf(Props[Echo].withRouter(RoundRobinRouter(2))
        .withDeploy(Deploy(scope = RemoteScope(AddressFromURIString("akka.tcp://remote-sys@localhost:12347")))), "remote-override")
      router.path.address.toString must be("akka.tcp://remote-sys@localhost:12347")
      val replies = for (i ← 1 to 5) yield {
        router ! ""
        expectMsgType[ActorRef].path
      }
      val children = replies.toSet
      children must have size 4
      val parents = children.map(_.parent)
      parents must have size 1
      parents.head must be(router.path)
      children foreach (_.address.toString must be === "akka.tcp://remote-sys@localhost:12347")
      system.stop(router)
    }

    "set supplied supervisorStrategy" in {
      val escalator = OneForOneStrategy() {
        case e ⇒ testActor ! e; SupervisorStrategy.Escalate
      }
      val router = system.actorOf(Props.empty.withRouter(new RemoteRouterConfig(
        RoundRobinRouter(1, supervisorStrategy = escalator),
        Seq(Address("akka.tcp", "remote-sys", "localhost", 12347)))), "blub3")

      router ! CurrentRoutees
      EventFilter[ActorKilledException](occurrences = 1) intercept {
        expectMsgType[RouterRoutees].routees.head ! Kill
      }
      expectMsgType[ActorKilledException]
    }

  }

  override def beforeTermination() {
    system.eventStream.publish(TestEvent.Mute(
      EventFilter.warning(pattern = "received dead letter.*Disassociate")))
  }

}
