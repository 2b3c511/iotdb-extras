/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.spark.tsfile

import java.util

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.FileStatus
import org.apache.iotdb.hadoop.fileSystem.HDFSInput
import org.apache.tsfile.common.constant.QueryConstant
import org.apache.tsfile.file.metadata.TsFileMetadata
import org.apache.tsfile.enums.TSDataType
import org.apache.tsfile.file.metadata.enums.TSEncoding
import org.apache.tsfile.common.conf.TSFileConfig
import org.apache.tsfile.read.TsFileSequenceReader
import org.apache.tsfile.read.common.Path
import org.apache.tsfile.read.expression.impl.{BinaryExpression, GlobalTimeExpression, SingleSeriesExpression}
import org.apache.tsfile.read.expression.{IExpression, QueryExpression}
import org.apache.tsfile.read.filter.factory.{TimeFilterApi, ValueFilterApi}
import org.apache.tsfile.utils.Binary
import org.apache.tsfile.write.record.TSRecord
import org.apache.tsfile.write.record.datapoint.DataPoint
import org.apache.tsfile.write.schema.{IMeasurementSchema, MeasurementSchema, Schema}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.sources._
import org.apache.spark.sql.types._

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer


/**
  * This object contains methods that are used to convert schema and data between SparkSQL
  * and TSFile.
  *
  */
object WideConverter extends Converter {

  val TEMPLATE_NAME = "spark_template"

  /**
    * Get series from the given tsFileMetaData.
    *
    * @param tsFileMetaData TsFileMetaData
    * @return union series
    */
  def getSeries(tsFileMetaData: TsFileMetadata, reader: TsFileSequenceReader): util.ArrayList[Series] = {
    val series = new util.ArrayList[Series]()

    val devices = reader.getAllDevices
    val measurements = reader.getAllMeasurements

    devices.foreach(d => {
      measurements.foreach(m => {
        val fullPath = d + "." + m._1
        series.add(new Series(fullPath, m._2)
        )
      })
    })

    series
  }

  /**
    * Get union series in all tsfiles.
    * e.g. (tsfile1:s1,s2) & (tsfile2:s2,s3) = s1,s2,s3
    *
    * @param files tsfiles
    * @param conf  hadoop configuration
    * @return union series
    */
  def getUnionSeries(files: Seq[FileStatus], conf: Configuration): util.ArrayList[Series] = {
    val unionSeries = new util.ArrayList[Series]()
    var seriesSet: mutable.Set[String] = mutable.Set()

    files.foreach(f => {
      val in = new HDFSInput(f.getPath, conf)
      val reader = new TsFileSequenceReader(in)
      val devices = reader.getAllDevices
      val measurements = reader.getAllMeasurements

      devices.foreach(d => {
        measurements.foreach(m => {
          val fullPath = d + "." + m._1
          if (!seriesSet.contains(fullPath)) {
            seriesSet += fullPath
            unionSeries.add(new Series(fullPath, m._2)
            )
          }
        })
      })
      reader.close()
    })

    unionSeries
  }

  /**
    * Prepare queriedSchema from requiredSchema.
    *
    * @param requiredSchema requiredSchema
    * @param tsFileMetaData tsFileMetaData
    * @return
    */
  def prepSchema(requiredSchema: StructType, tsFileMetaData: TsFileMetadata,
                 reader: TsFileSequenceReader): StructType = {
    var queriedSchema: StructType = new StructType()

    if (requiredSchema.isEmpty
      || (requiredSchema.size == 1 && requiredSchema.iterator.next().name ==
      QueryConstant.RESERVED_TIME)) {
      // for example, (i) select count(*) from table; (ii) select time from table

      val fileSchema = WideConverter.getSeries(tsFileMetaData, reader)
      queriedSchema = StructType(toSqlField(fileSchema, false).toList)

    } else { // Remove nonexistent schema according to the current file's metadata.
      // This may happen when queried TsFiles in the same folder do not have the same schema.

      val devices = reader.getAllDevices
      val measurementIds = reader.getAllMeasurements.keySet()
      requiredSchema.foreach(f => {
        if (!QueryConstant.RESERVED_TIME.equals(f.name)) {
          val path = new org.apache.tsfile.read.common.Path(f.name, true)
          if (devices.contains(path.getDeviceString) && measurementIds.contains(path.getMeasurement)) {
            queriedSchema = queriedSchema.add(f)
          }
        }
      })

    }

    queriedSchema
  }


  /**
    * Construct queryExpression based on queriedSchema and filters.
    *
    * @param schema  selected columns
    * @param filters filters
    * @return query expression
    */
  def toQueryExpression(schema: StructType, filters: Seq[Filter]): QueryExpression = {
    //get paths from schema
    val paths = new util.ArrayList[org.apache.tsfile.read.common.Path]
    schema.foreach(f => {
      if (!QueryConstant.RESERVED_TIME.equals(f.name)) { // the time field is excluded
        paths.add(new org.apache.tsfile.read.common.Path(f.name, true))
      }
    })

    //remove invalid filters
    val validFilters = new ListBuffer[Filter]()
    filters.foreach { f => {
      if (isValidFilter(f))
        validFilters.add(f)
    }
    }
    if (validFilters.isEmpty) {
      val queryExpression = QueryExpression.create(paths, null)
      queryExpression
    } else {
      //construct filters to a binary tree
      var filterTree = validFilters.get(0)
      for (i <- 1 until validFilters.length) {
        filterTree = And(filterTree, validFilters.get(i))
      }

      //convert filterTree to FilterOperator
      val finalFilter = transformFilter(schema, filterTree)

      // create query expression
      val queryExpression = QueryExpression.create(paths, finalFilter)

      queryExpression
    }
  }

  /**
    * Construct fields with the TSFile data type converted to the SparkSQL data type.
    *
    * @param tsfileSchema tsfileSchema
    * @param addTimeField true to add a time field; false to not
    * @return the converted list of fields
    */
  def toSqlField(tsfileSchema: util.ArrayList[Series], addTimeField: Boolean):
  ListBuffer[StructField] = {
    val fields = new ListBuffer[StructField]()

    if (addTimeField) {
      fields += StructField(QueryConstant.RESERVED_TIME, LongType, nullable = false)
    }

    tsfileSchema.foreach((series: Series) => {
      fields += StructField(series.getName, series.getType match {
        case TSDataType.BOOLEAN => BooleanType
        case TSDataType.INT32 => IntegerType
        case TSDataType.INT64 => LongType
        case TSDataType.FLOAT => FloatType
        case TSDataType.DOUBLE => DoubleType
        case TSDataType.TEXT => StringType
        case other => throw new UnsupportedOperationException(s"Unsupported type $other")
      }, nullable = true)
    })

    fields
  }

  /**
    * Transform SparkSQL's filter binary tree to TsFile's filter expression.
    *
    * @param schema to get relative columns' dataType information
    * @param node   filter tree's node
    * @return TSFile filter expression
    */
  private def transformFilter(schema: StructType, node: Filter): IExpression = {
    var filter: IExpression = null
    node match {
      case node: Not =>
        throw new Exception("NOT filter is not supported now")

      case node: And =>
        filter = BinaryExpression.and(transformFilter(schema, node.left), transformFilter(schema,
          node.right))
        filter

      case node: Or =>
        filter = BinaryExpression.or(transformFilter(schema, node.left), transformFilter(schema,
          node.right))
        filter

      case node: EqualTo =>
        if (QueryConstant.RESERVED_TIME.equals(node.attribute.toLowerCase())) {
          filter = new GlobalTimeExpression(TimeFilterApi.eq(node.value.asInstanceOf[java.lang.Long]))
        } else {
          filter = constructFilter(schema, node.attribute, node.value, FilterTypes.Eq)
        }
        filter

      case node: LessThan =>
        if (QueryConstant.RESERVED_TIME.equals(node.attribute.toLowerCase())) {
          filter = new GlobalTimeExpression(TimeFilterApi.lt(node.value.asInstanceOf[java.lang.Long]))
        } else {
          filter = constructFilter(schema, node.attribute, node.value, FilterTypes.Lt)
        }
        filter

      case node: LessThanOrEqual =>
        if (QueryConstant.RESERVED_TIME.equals(node.attribute.toLowerCase())) {
          filter = new GlobalTimeExpression(TimeFilterApi.ltEq(node.value.asInstanceOf[java.lang.Long]))
        } else {
          filter = constructFilter(schema, node.attribute, node.value, FilterTypes.LtEq)
        }
        filter

      case node: GreaterThan =>
        if (QueryConstant.RESERVED_TIME.equals(node.attribute.toLowerCase())) {
          filter = new GlobalTimeExpression(TimeFilterApi.gt(node.value.asInstanceOf[java.lang.Long]))
        } else {
          filter = constructFilter(schema, node.attribute, node.value, FilterTypes.Gt)
        }
        filter

      case node: GreaterThanOrEqual =>
        if (QueryConstant.RESERVED_TIME.equals(node.attribute.toLowerCase())) {
          filter = new GlobalTimeExpression(TimeFilterApi.gtEq(node.value.asInstanceOf[java.lang.Long]))
        } else {
          filter = constructFilter(schema, node.attribute, node.value, FilterTypes.GtEq)
        }
        filter

      case other =>
        throw new Exception(s"Unsupported filter $other")
    }
  }

  def constructFilter(schema: StructType, nodeName: String, nodeValue: Any,
                      filterType: FilterTypes.Value): IExpression = {
    val fieldNames = schema.fieldNames
    val index = fieldNames.indexOf(nodeName)
    if (index == -1) {
      // placeholder for an invalid filter in the current TsFile
      val filter = new SingleSeriesExpression(new Path(nodeName, true), null)
      filter
    } else {
      val dataType = schema.get(index).dataType

      filterType match {
        case FilterTypes.Eq =>
          dataType match {
            case BooleanType =>
              val filter = new SingleSeriesExpression(new Path(nodeName, true),
                ValueFilterApi.eq(index, nodeValue.asInstanceOf[java.lang.Boolean], TSDataType.BOOLEAN))
              filter
            case IntegerType =>
              val filter = new SingleSeriesExpression(new Path(nodeName, true),
                ValueFilterApi.eq(index, nodeValue.asInstanceOf[java.lang.Integer], TSDataType.INT32))
              filter
            case LongType =>
              val filter = new SingleSeriesExpression(new Path(nodeName, true),
                ValueFilterApi.eq(index, nodeValue.asInstanceOf[java.lang.Long], TSDataType.INT64))
              filter
            case FloatType =>
              val filter = new SingleSeriesExpression(new Path(nodeName, true),
                ValueFilterApi.eq(index, nodeValue.asInstanceOf[java.lang.Float], TSDataType.FLOAT))
              filter
            case DoubleType =>
              val filter = new SingleSeriesExpression(new Path(nodeName, true),
                ValueFilterApi.eq(index, nodeValue.asInstanceOf[java.lang.Double], TSDataType.DOUBLE))
              filter
            case StringType =>
              val filter = new SingleSeriesExpression(new Path(nodeName, true),
                ValueFilterApi.eq(index, new Binary(nodeValue.toString, TSFileConfig.STRING_CHARSET), TSDataType.TEXT))
              filter
            case other => throw new UnsupportedOperationException(s"Unsupported type $other")
          }
        case FilterTypes.Gt =>
          dataType match {
            case IntegerType =>
              val filter = new SingleSeriesExpression(new Path(nodeName, true),
                ValueFilterApi.gt(index, nodeValue.asInstanceOf[java.lang.Integer], TSDataType.INT32))
              filter
            case LongType =>
              val filter = new SingleSeriesExpression(new Path(nodeName, true),
                ValueFilterApi.gt(index, nodeValue.asInstanceOf[java.lang.Long], TSDataType.INT64))
              filter
            case FloatType =>
              val filter = new SingleSeriesExpression(new Path(nodeName, true),
                ValueFilterApi.gt(index, nodeValue.asInstanceOf[java.lang.Float], TSDataType.FLOAT))
              filter
            case DoubleType =>
              val filter = new SingleSeriesExpression(new Path(nodeName, true),
                ValueFilterApi.gt(index, nodeValue.asInstanceOf[java.lang.Double], TSDataType.DOUBLE))
              filter
            case other => throw new UnsupportedOperationException(s"Unsupported type $other")
          }
        case FilterTypes.GtEq =>
          dataType match {
            case IntegerType =>
              val filter = new SingleSeriesExpression(new Path(nodeName, true),
                ValueFilterApi.gtEq(index, nodeValue.asInstanceOf[java.lang.Integer], TSDataType.INT32))
              filter
            case LongType =>
              val filter = new SingleSeriesExpression(new Path(nodeName, true),
                ValueFilterApi.gtEq(index, nodeValue.asInstanceOf[java.lang.Long], TSDataType.INT64))
              filter
            case FloatType =>
              val filter = new SingleSeriesExpression(new Path(nodeName, true),
                ValueFilterApi.gtEq(index, nodeValue.asInstanceOf[java.lang.Float], TSDataType.FLOAT))
              filter
            case DoubleType =>
              val filter = new SingleSeriesExpression(new Path(nodeName, true),
                ValueFilterApi.gtEq(index, nodeValue.asInstanceOf[java.lang.Double], TSDataType.DOUBLE))
              filter
            case other => throw new UnsupportedOperationException(s"Unsupported type $other")
          }
        case FilterTypes.Lt =>
          dataType match {
            case IntegerType =>
              val filter = new SingleSeriesExpression(new Path(nodeName, true),
                ValueFilterApi.lt(index, nodeValue.asInstanceOf[java.lang.Integer], TSDataType.INT32))
              filter
            case LongType =>
              val filter = new SingleSeriesExpression(new Path(nodeName, true),
                ValueFilterApi.lt(index, nodeValue.asInstanceOf[java.lang.Long], TSDataType.INT64))
              filter
            case FloatType =>
              val filter = new SingleSeriesExpression(new Path(nodeName, true),
                ValueFilterApi.lt(index, nodeValue.asInstanceOf[java.lang.Float], TSDataType.FLOAT))
              filter
            case DoubleType =>
              val filter = new SingleSeriesExpression(new Path(nodeName, true),
                ValueFilterApi.lt(index, nodeValue.asInstanceOf[java.lang.Double], TSDataType.DOUBLE))
              filter
            case other => throw new UnsupportedOperationException(s"Unsupported type $other")
          }
        case FilterTypes.LtEq =>
          dataType match {
            case IntegerType =>
              val filter = new SingleSeriesExpression(new Path(nodeName, true),
                ValueFilterApi.ltEq(index, nodeValue.asInstanceOf[java.lang.Integer], TSDataType.INT32))
              filter
            case LongType =>
              val filter = new SingleSeriesExpression(new Path(nodeName, true),
                ValueFilterApi.ltEq(index, nodeValue.asInstanceOf[java.lang.Long], TSDataType.INT64))
              filter
            case FloatType =>
              val filter = new SingleSeriesExpression(new Path(nodeName, true),
                ValueFilterApi.ltEq(index, nodeValue.asInstanceOf[java.lang.Float], TSDataType.FLOAT))
              filter
            case DoubleType =>
              val filter = new SingleSeriesExpression(new Path(nodeName, true),
                ValueFilterApi.ltEq(index, nodeValue.asInstanceOf[java.lang.Double], TSDataType.DOUBLE))
              filter
            case other => throw new UnsupportedOperationException(s"Unsupported type $other")
          }
      }
    }
  }

  /**
    * Construct MeasurementSchema from the given field.
    *
    * @param field   field
    * @param options encoding options
    * @return MeasurementSchema
    */
  def getSeriesSchema(field: StructField, options: Map[String, String]): MeasurementSchema = {
    val dataType = getTsDataType(field.dataType)
    val encodingStr = dataType match {
      case TSDataType.BOOLEAN => options.getOrElse(QueryConstant.BOOLEAN, TSEncoding.PLAIN.toString)
      case TSDataType.INT32 => options.getOrElse(QueryConstant.INT32, TSEncoding.RLE.toString)
      case TSDataType.INT64 => options.getOrElse(QueryConstant.INT64, TSEncoding.RLE.toString)
      case TSDataType.FLOAT => options.getOrElse(QueryConstant.FLOAT, TSEncoding.RLE.toString)
      case TSDataType.DOUBLE => options.getOrElse(QueryConstant.DOUBLE, TSEncoding.RLE.toString)
      case TSDataType.TEXT => options.getOrElse(QueryConstant.BYTE_ARRAY, TSEncoding.PLAIN.toString)
      case other => throw new UnsupportedOperationException(s"Unsupported type $other")
    }
    val encoding = TSEncoding.valueOf(encodingStr)
    val fullPath = new Path(field.name, true)
    val measurement = fullPath.getMeasurement
    new MeasurementSchema(measurement, dataType, encoding)
  }

  /**
    * Given a SparkSQL struct type, generate the TsFile schema.
    * Note: Measurements of the same name should have the same schema.
    *
    * @param structType given sql schema
    * @return TsFile schema
    */
  def toTsFileSchema(structType: StructType, options: Map[String, String]): Schema = {
    val schema = new Schema()
    structType.fields.filter(f => {
      !QueryConstant.RESERVED_TIME.equals(f.name)
    }).foreach(f => {
      val seriesSchema = getSeriesSchema(f, options)
      schema.extendTemplate(TEMPLATE_NAME, seriesSchema)
    })
    schema
  }

  /**
    * Convert a row in the spark table to a list of TSRecord.
    *
    * @param row given spark sql row
    * @return TSRecord
    */
  def toTsRecord(row: InternalRow, dataSchema: StructType): List[TSRecord] = {
    val time = row.getLong(0)
    val deviceToRecord = scala.collection.mutable.Map[String, TSRecord]()
    var index = 1

    dataSchema.fields.filter(f => {
      !QueryConstant.RESERVED_TIME.equals(f.name)
    }).foreach(f => {
      val name = f.name
      val fullPath = new Path(name, true)
      val device = fullPath.getDeviceString
      val measurement = fullPath.getMeasurement

      if (!deviceToRecord.contains(device)) {
        deviceToRecord.put(device, new TSRecord(device, time))
      }
      val tsRecord: TSRecord = deviceToRecord.getOrElse(device, new TSRecord(device, time))

      val dataType = getTsDataType(f.dataType)
      if (!row.isNullAt(index)) {
        val value = f.dataType match {
          case BooleanType => row.getBoolean(index)
          case IntegerType => row.getInt(index)
          case LongType => row.getLong(index)
          case FloatType => row.getFloat(index)
          case DoubleType => row.getDouble(index)
          case StringType => row.getString(index)
          case other => throw new UnsupportedOperationException(s"Unsupported type $other")
        }
        val dataPoint = DataPoint.getDataPoint(dataType, measurement, value.toString)
        tsRecord.addTuple(dataPoint)
      }
      index += 1
    })
    deviceToRecord.values.toList
  }
}
