package com.patson.init

import com.patson.{Authentication, DemandGenerator, Util}
import com.patson.data._
import com.patson.data.airplane._
import com.patson.init.GeoDataGenerator.calculateLongitudeBoundary
import com.patson.model._
import com.patson.model.airplane._

import java.util.Calendar
import scala.collection.mutable
import scala.collection.mutable.{ListBuffer, Set}
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.Random


object GenericTransitGenerator {

  //larger airport goes first
  val TRANSIT_MANUAL_LINKS = Map(
    "HND" -> "NRT",
    "PEK" -> "PKX",
    "PKX" -> "TSN",
    "CTU" -> "TFU",
    "IST" -> "SAW",
    "SAW" -> "YEI",
    "EWR" -> "TTN",
    "HPN" -> "EWR",
    "HVN" -> "HPN",
    "TTN" -> "PHL",
    "PHL" -> "ACY",
    "JFK" -> "ISP",
    "ORH" -> "BOS",
    "BOS" -> "PVD",
    "MIA" -> "PBI",
    "FLL" -> "PBI",
    "JAX" -> "UST",
    "DAB" -> "UST",
    "VPS" -> "ECP",
    "VPS" -> "PNS",
    "ELP" -> "LRU",
    "SLC" -> "PVU",
    "MWA" -> "CGI",
    "MWA" -> "PAH",
    "LAX" -> "ONT",
    "SNA" -> "ONT",
    "SMF" -> "SCK",
    "OAK" -> "SCK",
    "STL" -> "BLV",
    "SEA" -> "PAE",
    "KOA" -> "ITO",
    "YYZ" -> "YKF",
    "YYZ" -> "YHM",
    "YYZ" -> "YKF",
    "YVR" -> "YXX",
    "FAI" -> "CKX",
    "MEX" -> "TLC",
    "GRU" -> "VCP",
    "CGH" -> "VCP",
    "AMS" -> "EIN",
    "EIN" -> "MST",
    "RTM" -> "EIN",
    "DUS" -> "CGN",
    "HAM" -> "LBC",
    "OSL" -> "TRF",
    "ARN" -> "NYO",
    "LGW" -> "LTN",
    "CDG" -> "BVA",
    "MRS" -> "TLN",
    "MRS" -> "AVN",
    "BCN" -> "REU",
    "ZRH" -> "BRN",
    "GVA" -> "BRN",
    "BSL" -> "BRN",
    "ZRH" -> "BSL",
    "RMI" -> "AOI",
    "BLQ" -> "FRL",
    "NAP" -> "QSR",
    "MEL" -> "AVV",
    "ZIA" -> "SVO",
    "DME" -> "SVO"
  )
  val ISLANDS = List(
    "MQS", "GST", "VQS", "CPX", "GBJ", "SBH", "SAB", "UNI", "STT", "EIS", "AXA", "PLS", "GDT", "SVD", "HID", "APW", "PPT", "TCB", "JIK", "ESD", "HHH", "SAQ", "TIQ", "HOR", "PIX", "KOI", "HTI",
    "SKN", "SSJ", "BNN", "MOL", //europe
    "ISC", "EG1", "FIE", "LSI", "ILY", "FOA",  //gb
    "NNR", //ie
    "GRW", "CVU", //pt
    "ECN", //turkish CY
    "JNX", //gr
    "KUM", //jp
    "TBH", //ph
    "NMF", "HRF", "KDM", "NAN", "MEE", "PTF", //oceania
    "HRF", "HDK", "PRI", //indian ocean
    "WRG", "MQC", //ca
    "MVY", "ACK", "ISP", "FRD", "ESD", "MKK", "LNY",  //us
  )

  def main(args : Array[String]) : Unit = {
    generateGenericTransit()
    Await.result(actorSystem.terminate(), Duration.Inf)
  }
  def generateGenericTransit(range : Int = 50) : Unit = {
    //purge existing generic transit
    LinkSource.deleteLinksByCriteria(List(("transport_type", TransportType.GENERIC_TRANSIT.id)))

    val airports = AirportSource.loadAllAirports(true).filter(_.population >= 500).filter(_.runwayLength >= 500).filter { airport => !ISLANDS.contains(airport.iata) }.sortBy { _.power }

    var counter = 0;
    var progressCount = 0;

    val processed = mutable.HashSet[(Int, Int)]()
    val countryRelationships = CountrySource.getCountryMutualRelationships()
    for (airport <- airports) {
      //calculate max and min longitude that we should kick off the calculation
      val boundaryLongitude = calculateLongitudeBoundary(airport.latitude, airport.longitude, range)
      val airportsInRange = scala.collection.mutable.ListBuffer[(Airport, Double)]()
      for (targetAirport <- airports) {
        if (!processed.contains((targetAirport.id, airport.id)) && //check the swap pairs are not processed already to avoid duplicates
          (TRANSIT_MANUAL_LINKS.get(airport.iata).contains(targetAirport.iata) || TRANSIT_MANUAL_LINKS.get(targetAirport.iata).contains(airport.iata))
        ) {
          val distance = Util.calculateDistance(airport.latitude, airport.longitude, targetAirport.latitude, targetAirport.longitude).toInt
          airportsInRange += Tuple2(targetAirport, distance)
        } else if (airport.id != targetAirport.id &&
            targetAirport.popMiddleIncome > 2500 &&
            !processed.contains((targetAirport.id, airport.id)) && //check the swap pairs are not processed already to avoid duplicates
            airport.longitude >= boundaryLongitude._1 && airport.longitude <= boundaryLongitude._2 &&
            countryRelationships.getOrElse((airport.countryCode, targetAirport.countryCode), 0) >= 2
        ) {
          val distance = Util.calculateDistance(airport.latitude, airport.longitude, targetAirport.latitude, targetAirport.longitude).toInt
          if (range >= distance) {
            airportsInRange += Tuple2(targetAirport, distance)
          }
        }
        processed.add((airport.id, targetAirport.id))
      }




      airportsInRange.foreach { case (targetAirport, distance) =>
        val domesticAirportBonus = if(targetAirport.isDomesticAirport() || airport.isDomesticAirport()){
          30000
        } else {
          0
        }
        val minSize = Math.min(airport.size, targetAirport.size)
        val capacity = minSize * 9000 + domesticAirportBonus //kinda random
        val genericTransit = GenericTransit(from = airport, to = targetAirport, distance = distance.toInt, capacity = LinkClassValues.getInstance(economy = capacity))
        LinkSource.saveLink(genericTransit)
        println(genericTransit)
      }

      val progressChunk = airports.size / 100
      counter += 1
      if (counter % progressChunk == 0) {
        progressCount += 1;
        print(".")
        if (progressCount % 10 == 0) {
          print(progressCount + "% ")
        }
      }
    }
  }
}