package ch.ehi.ili2db.toxtf;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;

import ch.ehi.basics.logging.EhiLogger;
import ch.ehi.ili2db.base.DbIdGen;
import ch.ehi.ili2db.base.DbNames;
import ch.ehi.ili2db.base.IliNames;
import ch.ehi.ili2db.converter.AbstractRecordConverter;
import ch.ehi.ili2db.converter.ConverterException;
import ch.ehi.ili2db.converter.SqlColumnConverter;
import ch.ehi.ili2db.gui.Config;
import ch.ehi.ili2db.mapping.ArrayMapping;
import ch.ehi.ili2db.mapping.MultiLineMapping;
import ch.ehi.ili2db.mapping.MultiPointMapping;
import ch.ehi.ili2db.mapping.MultiSurfaceMapping;
import ch.ehi.ili2db.mapping.NameMapping;
import ch.ehi.ili2db.mapping.TrafoConfig;
import ch.ehi.ili2db.mapping.TrafoConfigNames;
import ch.ehi.ili2db.mapping.Viewable2TableMapping;
import ch.ehi.ili2db.mapping.ViewableWrapper;
import ch.interlis.ili2c.metamodel.AreaType;
import ch.interlis.ili2c.metamodel.AssociationDef;
import ch.interlis.ili2c.metamodel.AttributeDef;
import ch.interlis.ili2c.metamodel.BlackboxType;
import ch.interlis.ili2c.metamodel.CompositionType;
import ch.interlis.ili2c.metamodel.CoordType;
import ch.interlis.ili2c.metamodel.EnumerationType;
import ch.interlis.ili2c.metamodel.ObjectType;
import ch.interlis.ili2c.metamodel.PolylineType;
import ch.interlis.ili2c.metamodel.ReferenceType;
import ch.interlis.ili2c.metamodel.RoleDef;
import ch.interlis.ili2c.metamodel.SurfaceOrAreaType;
import ch.interlis.ili2c.metamodel.SurfaceType;
import ch.interlis.ili2c.metamodel.Table;
import ch.interlis.ili2c.metamodel.TransferDescription;
import ch.interlis.ili2c.metamodel.Type;
import ch.interlis.ili2c.metamodel.Viewable;
import ch.interlis.ili2c.metamodel.ViewableTransferElement;
import ch.interlis.iom.IomObject;
import ch.interlis.iom_j.Iom_jObject;
import ch.interlis.iom_j.itf.ItfWriter2;
import ch.interlis.iox_j.wkb.Wkb2iox;

public class ToXtfRecordConverter extends AbstractRecordConverter {
	private boolean isMsAccess=false;
	private Connection conn=null;
	private SqlColumnConverter geomConv=null;
	private SqlidPool sqlid2xtfid=null;
	public final static java.util.Date PURE_GREGORIAN_CALENDAR = new java.util.Date(Long.MIN_VALUE);
	public ToXtfRecordConverter(TransferDescription td1, NameMapping ili2sqlName,
			Config config, DbIdGen idGen1,SqlColumnConverter geomConv1,Connection conn1,SqlidPool sqlidPool,TrafoConfig trafoConfig,Viewable2TableMapping class2wrapper1) {
		super(td1, ili2sqlName, config, idGen1,trafoConfig,class2wrapper1);
		conn=conn1;
		geomConv=geomConv1;
		sqlid2xtfid=sqlidPool;
		try {
			if(conn.getMetaData().getURL().startsWith("jdbc:odbc:DRIVER={Microsoft Access Driver (*.mdb)}")){
				isMsAccess=true;
			}
		} catch (SQLException e) {
			EhiLogger.logError(e);
		}
	}
	/** creates sql query statement for a class.
	 * @param aclass type of objects to build query for
	 * @param wrapper not null, if building query for struct values
	 * @return SQL-Query statement
	 */
	public String createQueryStmt(Viewable aclass1,Long basketSqlId,StructWrapper structWrapper){
		ViewableWrapper aclass=class2wrapper.get(aclass1);
		ViewableWrapper rootWrapper=aclass.getWrappers().get(0);
		StringBuffer ret = new StringBuffer();
		ret.append("SELECT r0."+colT_ID);
		if(createTypeDiscriminator || aclass.includesMultipleTypes()){
			ret.append(", r0."+DbNames.T_TYPE_COL);
		}
		if(!aclass.isStructure()){
			if(createIliTidCol || aclass.getOid()!=null){
				ret.append(", r0."+DbNames.T_ILI_TID_COL);
			}
		}
		if(structWrapper!=null){
			if(createGenericStructRef){
				ret.append(", r0."+DbNames.T_PARENT_ID_COL);
				ret.append(", r0."+DbNames.T_PARENT_TYPE_COL);
				ret.append(", r0."+DbNames.T_PARENT_ATTR_COL);
			}else{
				ret.append(", r0."+ili2sqlName.mapIliAttributeDefReverse(structWrapper.getParentAttr(),getSqlType(aclass.getViewable()).getName(),getSqlType(structWrapper.getParentTable().getViewable()).getName()));
			}
			ret.append(", r0."+DbNames.T_SEQ_COL);
		}
		String sep=",";
		int tableAliasIdx=0;
		HashSet<AttributeDef> visitedAttrs=new HashSet<AttributeDef>();
		for(ViewableWrapper table:aclass.getWrappers()){
			String tableAlias = "r"+tableAliasIdx;
			String sqlTableName=table.getSqlTablename();
			Iterator iter = table.getAttrIterator();
			while (iter.hasNext()) {
			   ViewableTransferElement obj = (ViewableTransferElement)iter.next();
			   if (obj.obj instanceof AttributeDef) {
				   AttributeDef attr = (AttributeDef) obj.obj;
				   AttributeDef baseAttr=attr;
				   while(true){
					   AttributeDef baseAttr1=(AttributeDef)baseAttr.getExtending();
					   if(baseAttr1==null){
						   break;
					   }
					   baseAttr=baseAttr1;
				   }
					if(!visitedAttrs.contains(baseAttr)){
						visitedAttrs.add(baseAttr);
						if(!baseAttr.isTransient()){
							Type proxyType=baseAttr.getDomain();
							if(proxyType!=null && (proxyType instanceof ObjectType)){
								// skip implicit particles (base-viewables) of views
							}else{
								 sep = addAttrToQueryStmt(ret, sep, tableAlias,baseAttr,sqlTableName);
							}
						}
					}
			   }
			   if(obj.obj instanceof RoleDef){
				   RoleDef role = (RoleDef) obj.obj;
				   if(role.getExtending()==null){
						ArrayList<ViewableWrapper> targetTables = getTargetTables(role.getDestination());
						  for(ViewableWrapper targetTable : targetTables){
								String roleSqlName=ili2sqlName.mapIliRoleDef(role,sqlTableName,targetTable.getSqlTablename(),targetTables.size()>1);
								// a role of an embedded association?
								if(obj.embedded){
									AssociationDef roleOwner = (AssociationDef) role.getContainer();
									if(roleOwner.getDerivedFrom()==null){
										 // TODO if(orderPos!=0){
										 ret.append(sep);
										 sep=",";
										 ret.append(makeColumnRef(tableAlias,roleSqlName));
									}
								 }else{
									 // TODO if(orderPos!=0){
									 ret.append(sep);
									 sep=",";
									 ret.append(makeColumnRef(tableAlias,roleSqlName));
								 }
						  }
				   }
				}
			}
			tableAliasIdx++; // next table alias
		}
		// stdcols
		if(createStdCols){
			ret.append(sep);
			sep=",";
			ret.append("r0."+DbNames.T_LAST_CHANGE_COL);
			ret.append(sep);
			sep=",";
			ret.append("r0."+DbNames.T_CREATE_DATE_COL);
			ret.append(sep);
			sep=",";
			ret.append("r0."+DbNames.T_USER_COL);
		}

		ret.append(" FROM ");
		ArrayList<ViewableWrapper> tablev=new ArrayList<ViewableWrapper>(10);
		tablev.addAll(aclass.getWrappers());
		sep="";
		int tablec=tablev.size();
		if(isMsAccess){
			for(int i=0;i<tablec;i++){
				ret.append("(");
			}
		}
		for(int i=0;i<tablec;i++){
			ret.append(sep);
			ret.append(tablev.get(i).getSqlTableQName());
			ret.append(" r"+Integer.toString(i));
			if(i>0){
				ret.append(" ON r0."+colT_ID+"=r"+Integer.toString(i)+"."+colT_ID);
			}
			if(isMsAccess){
				ret.append(")");
			}
			sep=" LEFT JOIN ";
		}
		sep=" WHERE";
		if(createTypeDiscriminator || rootWrapper.includesMultipleTypes()){
			ret.append(sep+" r0."+DbNames.T_TYPE_COL+"='"+getSqlType(aclass1).getName()+"'");
			sep=" AND";
		}
		if(structWrapper!=null){
			if(createGenericStructRef){
				ret.append(sep+" r0."+DbNames.T_PARENT_ID_COL+"=? AND r0."+DbNames.T_PARENT_ATTR_COL+"=?");
			}else{
				ret.append(sep+" r0."+ili2sqlName.mapIliAttributeDefReverse(structWrapper.getParentAttr(),getSqlType(aclass.getViewable()).getName(),getSqlType(structWrapper.getParentTable().getViewable()).getName())+"=?");
			}
			sep=" AND";
		}
		if(basketSqlId!=null){
			ret.append(sep+" r0."+DbNames.T_BASKET_COL+"=?");
		}
		if(structWrapper!=null){
			ret.append(" ORDER BY r0."+DbNames.T_SEQ_COL+" ASC");
		}
		return ret.toString();
	}
	private String makeColumnRef(String tableAlias, String columnName) {
		if(tableAlias==null){
			return columnName;
		}
		return tableAlias+"."+columnName;
	}
	public String addAttrToQueryStmt(StringBuffer ret, String sep, String tableAlias,AttributeDef attr,String sqlTableName) {
		if(attr.getExtending()==null){
			Type type = attr.getDomainResolvingAliases();
			 String attrSqlName=ili2sqlName.mapIliAttributeDef(attr,sqlTableName,null);
			if( attr.isDomainIli1Date()) {
				 ret.append(sep);
				 sep=",";
				 ret.append(geomConv.getSelectValueWrapperDate(makeColumnRef(tableAlias,attrSqlName)));
			}else if( attr.isDomainIli2Date()) {
				 ret.append(sep);
				 sep=",";
				 ret.append(geomConv.getSelectValueWrapperDate(makeColumnRef(tableAlias,attrSqlName)));
			}else if( attr.isDomainIli2Time()) {
				 ret.append(sep);
				 sep=",";
				 ret.append(geomConv.getSelectValueWrapperTime(makeColumnRef(tableAlias,attrSqlName)));
			}else if( attr.isDomainIli2DateTime()) {
				 ret.append(sep);
				 sep=",";
				 ret.append(geomConv.getSelectValueWrapperDateTime(makeColumnRef(tableAlias,attrSqlName)));
			}else if (type instanceof CompositionType){
				if(TrafoConfigNames.CATALOGUE_REF_TRAFO_COALESCE.equals(trafoConfig.getAttrConfig(attr, TrafoConfigNames.CATALOGUE_REF_TRAFO))){
                    ArrayList<ViewableWrapper> targetTables = getTargetTables(getCatalogueRefTarget(type));
                    for(ViewableWrapper targetTable:targetTables)
                    {
                        attrSqlName=ili2sqlName.mapIliAttributeDef(attr,sqlTableName,targetTable.getSqlTablename(),targetTables.size()>1);
                         ret.append(sep);
                         sep=",";
                         ret.append(makeColumnRef(tableAlias,attrSqlName));
                    }                
				}else if(TrafoConfigNames.MULTISURFACE_TRAFO_COALESCE.equals(trafoConfig.getAttrConfig(attr, TrafoConfigNames.MULTISURFACE_TRAFO))){
					 ret.append(sep);
					 sep=",";
					 ret.append(geomConv.getSelectValueWrapperMultiSurface(makeColumnRef(tableAlias,attrSqlName)));
					 multiSurfaceAttrs.addMultiSurfaceAttr(attr);
				}else if(TrafoConfigNames.MULTILINE_TRAFO_COALESCE.equals(trafoConfig.getAttrConfig(attr, TrafoConfigNames.MULTILINE_TRAFO))){
					 ret.append(sep);
					 sep=",";
					 ret.append(geomConv.getSelectValueWrapperMultiPolyline(makeColumnRef(tableAlias,attrSqlName)));
					 multiLineAttrs.addMultiLineAttr(attr);
				}else if(TrafoConfigNames.MULTIPOINT_TRAFO_COALESCE.equals(trafoConfig.getAttrConfig(attr, TrafoConfigNames.MULTIPOINT_TRAFO))){
					 ret.append(sep);
					 sep=",";
					 ret.append(geomConv.getSelectValueWrapperMultiCoord(makeColumnRef(tableAlias,attrSqlName)));
					 multiPointAttrs.addMultiPointAttr(attr);
				}else if(TrafoConfigNames.ARRAY_TRAFO_COALESCE.equals(trafoConfig.getAttrConfig(attr, TrafoConfigNames.ARRAY_TRAFO))){
					 ret.append(sep);
					 sep=",";
					 ret.append(geomConv.getSelectValueWrapperArray(makeColumnRef(tableAlias,attrSqlName)));
					 arrayAttrs.addArrayAttr(attr);
				}else if(TrafoConfigNames.MULTILINGUAL_TRAFO_EXPAND.equals(trafoConfig.getAttrConfig(attr, TrafoConfigNames.MULTILINGUAL_TRAFO))){
					for(String sfx:DbNames.MULTILINGUAL_TXT_COL_SUFFIXS){
						 ret.append(sep);
						 sep=",";
						 ret.append(makeColumnRef(tableAlias,attrSqlName+sfx));
					}
				}
			}else if (type instanceof PolylineType){
				 ret.append(sep);
				 sep=",";
				 ret.append(geomConv.getSelectValueWrapperPolyline(makeColumnRef(tableAlias,attrSqlName)));
			 }else if(type instanceof SurfaceOrAreaType){
				 if(createItfLineTables){
				 }else{
					 ret.append(sep);
					 sep=",";
					 ret.append(geomConv.getSelectValueWrapperSurface(makeColumnRef(tableAlias,attrSqlName)));
				 }
				 if(createItfAreaRef){
					 if(type instanceof AreaType){
						 ret.append(sep);
						 sep=",";
						 ret.append(geomConv.getSelectValueWrapperCoord(makeColumnRef(tableAlias,attrSqlName+DbNames.ITF_MAINTABLE_GEOTABLEREF_COL_SUFFIX)));
					 }
				 }
			 }else if(type instanceof CoordType){
				 ret.append(sep);
				 sep=",";
				 ret.append(geomConv.getSelectValueWrapperCoord(makeColumnRef(tableAlias,attrSqlName)));
			 }else if(type instanceof ReferenceType){
					ArrayList<ViewableWrapper> targetTables = getTargetTables(((ReferenceType)type).getReferred());
					for(ViewableWrapper targetTable:targetTables)
					{
						attrSqlName=ili2sqlName.mapIliAttributeDef(attr,sqlTableName,targetTable.getSqlTablename(),targetTables.size()>1);
						 ret.append(sep);
						 sep=",";
						 ret.append(makeColumnRef(tableAlias,attrSqlName));
					}				 
			}else{
				 ret.append(sep);
				 sep=",";
				 ret.append(makeColumnRef(tableAlias,attrSqlName));
			}
		   }
		return sep;
	}
	public void setStmtParams(java.sql.PreparedStatement dbstmt,
			Long basketSqlId, FixIomObjectRefs fixref,
			StructWrapper structWrapper) throws SQLException {
		dbstmt.clearParameters();
		int paramIdx=1;
		if(structWrapper!=null){
			dbstmt.setLong(paramIdx++,structWrapper.getParentSqlId());
			if(createGenericStructRef){
				dbstmt.setString(paramIdx++,ili2sqlName.mapIliAttributeDef(structWrapper.getParentAttr(),getSqlType(structWrapper.getParentTable().getViewable()).getName(),null));
			}
		}else{
			if(fixref!=null){
				throw new IllegalArgumentException("fixref!=null");
			}
		}
		if(basketSqlId!=null){
			dbstmt.setLong(paramIdx++,basketSqlId);
		}
	}
	public long getT_ID(java.sql.ResultSet rs) throws SQLException {
		long sqlid=rs.getLong(1);
		return sqlid;
	}
	public Iom_jObject convertRecord(java.sql.ResultSet rs, Viewable aclass1,
			FixIomObjectRefs fixref, StructWrapper structWrapper,
			HashMap structelev, ArrayList<StructWrapper> structQueue, long sqlid)
			throws SQLException {
		ViewableWrapper aclass=class2wrapper.get(aclass1);
		Iom_jObject iomObj;
		int valuei=1;
		valuei++;
		if(createTypeDiscriminator || aclass.includesMultipleTypes()){
			//String t_type=rs.getString(valuei);
			valuei++;
		}
		String sqlIliTid=null;
		if(structWrapper==null){
			if(!aclass.isStructure()){
				if(createIliTidCol || aclass.getOid()!=null){
					sqlIliTid=rs.getString(valuei);
					sqlid2xtfid.putSqlid2Xtfid(sqlid, sqlIliTid);
					valuei++;
				}else{
					sqlIliTid=Long.toString(sqlid);
					sqlid2xtfid.putSqlid2Xtfid(sqlid, sqlIliTid);
				}
			}
		}
		if(structWrapper==null){
			if(!aclass.isStructure()){
				iomObj=new Iom_jObject(aclass1.getScopedName(null),sqlIliTid);
			}else{
				iomObj=new Iom_jObject(aclass1.getScopedName(null),null);
			}
			iomObj.setattrvalue(ItfWriter2.INTERNAL_T_ID, Long.toString(sqlid));
			fixref.setRoot(iomObj);
		}else{
			iomObj=(Iom_jObject)structelev.get(Long.toString(sqlid));
			if(createGenericStructRef){
				valuei+=4;
			}else{
				valuei+=2;
			}
		}
		HashSet<AttributeDef> visitedAttrs=new HashSet<AttributeDef>();
		for(ViewableWrapper table:aclass.getWrappers()){
			Iterator iter = table.getAttrIterator();
			while (iter.hasNext()) {
			   ViewableTransferElement obj = (ViewableTransferElement)iter.next();
			   if (obj.obj instanceof AttributeDef) {
				   AttributeDef attr = (AttributeDef) obj.obj;
				   AttributeDef baseAttr=attr;
				   while(true){
					   AttributeDef baseAttr1=(AttributeDef)baseAttr.getExtending();
					   if(baseAttr1==null){
						   break;
					   }
					   baseAttr=baseAttr1;
				   }
					if(!visitedAttrs.contains(baseAttr)){
						visitedAttrs.add(baseAttr);
						if(!baseAttr.isTransient()){
							Type proxyType=baseAttr.getDomain();
							if(proxyType!=null && (proxyType instanceof ObjectType)){
								// skip implicit particles (base-viewables) of views
							}else{
								   valuei = addAttrValue(rs, valuei, sqlid, iomObj, baseAttr,structQueue,table,fixref);
							}
						}
					}
			   }
			   if(obj.obj instanceof RoleDef){
				   RoleDef role = (RoleDef) obj.obj;
				   if(role.getExtending()==null){
					 String roleName=role.getName();
						ArrayList<ViewableWrapper> targetTables = getTargetTables(role.getDestination());
						boolean refAlreadyDefined=false;
						  for(ViewableWrapper targetTable : targetTables){
								 String sqlRoleName=ili2sqlName.mapIliRoleDef(role,getSqlType(table.getViewable()).getName(),targetTable.getSqlTablename(),targetTables.size()>1);
								 // a role of an embedded association?
								 if(obj.embedded){
									AssociationDef roleOwner = (AssociationDef) role.getContainer();
									if(roleOwner.getDerivedFrom()==null){
										 // TODO if(orderPos!=0){
										long value=rs.getLong(valuei);
										valuei++;
										if(!rs.wasNull()){
											if(refAlreadyDefined){
												EhiLogger.logAdaption("Table "+table.getSqlTablename()+"(id "+sqlid+") more than one value for role "+roleName+"; value of "+sqlRoleName+" ignored");
											}else{
												IomObject ref=iomObj.addattrobj(roleName,roleOwner.getScopedName(null));
												mapSqlid2Xtfid(fixref,value,ref,role.getDestination());
												refAlreadyDefined=true;
											}
										}
									}
								 }else{
									 // TODO if(orderPos!=0){
										long value=rs.getLong(valuei);
										valuei++;
										if(!rs.wasNull()){
											if(refAlreadyDefined){
												EhiLogger.logAdaption("Table "+table.getSqlTablename()+"(id "+sqlid+") more than one value for role "+roleName+"; value of "+sqlRoleName+" ignored");
											}else{
												IomObject ref=iomObj.addattrobj(roleName,"REF");
												mapSqlid2Xtfid(fixref,value,ref,role.getDestination());
												refAlreadyDefined=true;
											}
										}
								 }
							  
						  }
				   }
				}
			}
			
		}

		return iomObj;
	}
	
	final private int  LEN_LANG_PREFIX=DbNames.MULTILINGUAL_TXT_COL_PREFIX.length();
	public int addAttrValue(java.sql.ResultSet rs, int valuei, long sqlid,
			Iom_jObject iomObj, AttributeDef attr,ArrayList<StructWrapper> structQueue,ViewableWrapper table,FixIomObjectRefs fixref) throws SQLException {
		if(attr.getExtending()==null){
			String attrName=attr.getName();
			String sqlAttrName=ili2sqlName.mapIliAttributeDef(attr,table.getSqlTablename(),null);
			if( attr.isDomainBoolean()) {
					boolean value=rs.getBoolean(valuei);
					valuei++;
					if(!rs.wasNull()){
						if(value){
							iomObj.setattrvalue(attrName,"true");
						}else{
							iomObj.setattrvalue(attrName,"false");
						}
					}
			}else if( attr.isDomainIli1Date()) {
				java.sql.Date value=rs.getDate(valuei);
				valuei++;
				if(!rs.wasNull()){
					java.text.SimpleDateFormat fmt=new java.text.SimpleDateFormat("yyyyMMdd");
					GregorianCalendar date=new GregorianCalendar();
					date.setGregorianChange(PURE_GREGORIAN_CALENDAR);
					fmt.setCalendar(date);
					iomObj.setattrvalue(attrName,fmt.format(value));
				}
			}else if( attr.isDomainIli2Date()) {
				java.sql.Date value=rs.getDate(valuei);
				valuei++;
				if(!rs.wasNull()){
					java.text.SimpleDateFormat fmt=new java.text.SimpleDateFormat("yyyy-MM-dd");
					GregorianCalendar date=new GregorianCalendar();
					date.setGregorianChange(PURE_GREGORIAN_CALENDAR);
					fmt.setCalendar(date);
					iomObj.setattrvalue(attrName,fmt.format(value));
				}
			}else if( attr.isDomainIli2Time()) {
				java.sql.Time value=rs.getTime(valuei);
				valuei++;
				if(!rs.wasNull()){
					java.text.SimpleDateFormat fmt=new java.text.SimpleDateFormat("HH:mm:ss.SSS");
					iomObj.setattrvalue(attrName,fmt.format(value));
				}
			}else if( attr.isDomainIli2DateTime()) {
				java.sql.Timestamp value=rs.getTimestamp(valuei);
				valuei++;
				if(!rs.wasNull()){
					java.text.SimpleDateFormat fmt=new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS"); // with timezone: yyyy-MM-dd'T'HH:mm:ss.SSSZ 
					GregorianCalendar date=new GregorianCalendar();
					date.setGregorianChange(PURE_GREGORIAN_CALENDAR);
					fmt.setCalendar(date);
					iomObj.setattrvalue(attrName,fmt.format(value));
				}
			}else{
				Type type = attr.getDomainResolvingAliases();
				if (type instanceof CompositionType){
					if(TrafoConfigNames.CATALOGUE_REF_TRAFO_COALESCE.equals(trafoConfig.getAttrConfig(attr, TrafoConfigNames.CATALOGUE_REF_TRAFO))){
	                    ArrayList<ViewableWrapper> targetTables = getTargetTables(getCatalogueRefTarget(type));
	                    boolean refAlreadyDefined=false;
	                    for(ViewableWrapper targetTable:targetTables)
	                    {
	                        long value=rs.getLong(valuei);
	                        valuei++;
	                        if(!rs.wasNull()){
	                            if(refAlreadyDefined){
	                                sqlAttrName=ili2sqlName.mapIliAttributeDef(attr,table.getSqlTablename(),targetTable.getSqlTablename(),targetTables.size()>1);
	                                EhiLogger.logAdaption("Table "+table.getSqlTablename()+"(id "+sqlid+") more than one value for refattr "+attrName+"; value of "+sqlAttrName+" ignored");
	                            }else{
	                                Table catalogueReferenceTyp = ((CompositionType) type).getComponentType();
	                                IomObject catref=iomObj.addattrobj(attrName,catalogueReferenceTyp.getScopedName(null));
	                                IomObject ref=catref.addattrobj(IliNames.CHBASE1_CATALOGUEREFERENCE_REFERENCE,"REF");
	                                mapSqlid2Xtfid(fixref,value,ref,getCatalogueRefTarget(type));
                                    refAlreadyDefined=true;
	                            }
	                        }
	                    }
					}else if(TrafoConfigNames.MULTISURFACE_TRAFO_COALESCE.equals(trafoConfig.getAttrConfig(attr, TrafoConfigNames.MULTISURFACE_TRAFO))){
						 MultiSurfaceMapping attrMapping=multiSurfaceAttrs.getMapping(attr);
						Table multiSurfaceType = ((CompositionType) type).getComponentType();
						Table surfaceStructureType=((CompositionType) ((AttributeDef) multiSurfaceType.getElement(AttributeDef.class, attrMapping.getBagOfSurfacesAttrName())).getDomain()).getComponentType();
						String multiSurfaceQname=multiSurfaceType.getScopedName(null);
						String surfaceStructureQname=surfaceStructureType.getScopedName(null);
						SurfaceType surface=((SurfaceType) ((AttributeDef) surfaceStructureType.getElement(AttributeDef.class,attrMapping.getSurfaceAttrName())).getDomainResolvingAliases());
						CoordType coord=(CoordType)surface.getControlPointDomain().getType();
						boolean is3D=coord.getDimensions().length==3;
						Object geomobj=rs.getObject(valuei);
						valuei++;
						if(!rs.wasNull()){
							try{
								IomObject iomMultiSurface=geomConv.toIomMultiSurface(geomobj,sqlAttrName,is3D);
								IomObject iomChbaseMultiSurface=new Iom_jObject(multiSurfaceQname,null); 
								int surfacec=iomMultiSurface.getattrvaluecount("surface");
								for(int surfacei=0;surfacei<surfacec;surfacei++){
									IomObject iomSurface=iomMultiSurface.getattrobj("surface",surfacei);
									IomObject iomChbaseSurfaceStructure=iomChbaseMultiSurface.addattrobj(attrMapping.getBagOfSurfacesAttrName(), surfaceStructureQname);
									IomObject iomSurfaceClone=new ch.interlis.iom_j.Iom_jObject("MULTISURFACE",null);
									iomSurfaceClone.addattrobj("surface",iomSurface);
									iomChbaseSurfaceStructure.addattrobj(attrMapping.getSurfaceAttrName(), iomSurfaceClone);
								}
								iomObj.addattrobj(attrName,iomChbaseMultiSurface);
							}catch(ConverterException ex){
								EhiLogger.logError("Object "+sqlid+": failed to convert surface/area",ex);
							}	
						}
					}else if(TrafoConfigNames.MULTILINE_TRAFO_COALESCE.equals(trafoConfig.getAttrConfig(attr, TrafoConfigNames.MULTILINE_TRAFO))){
						 MultiLineMapping attrMapping=multiLineAttrs.getMapping(attr);
						Table multiLineType = ((CompositionType) type).getComponentType();
						Table lineStructureType=((CompositionType) ((AttributeDef) multiLineType.getElement(AttributeDef.class, attrMapping.getBagOfLinesAttrName())).getDomain()).getComponentType();
						String multiLineQname=multiLineType.getScopedName(null);
						String lineStructureQname=lineStructureType.getScopedName(null);
						PolylineType surface=((PolylineType) ((AttributeDef) lineStructureType.getElement(AttributeDef.class,attrMapping.getLineAttrName())).getDomainResolvingAliases());
						CoordType coord=(CoordType)surface.getControlPointDomain().getType();
						boolean is3D=coord.getDimensions().length==3;
						Object geomobj=rs.getObject(valuei);
						valuei++;
						if(!rs.wasNull()){
							try{
								IomObject iomMultiPolygon=geomConv.toIomMultiPolyline(geomobj,sqlAttrName,is3D);
								IomObject iomChbaseMultiLine=new Iom_jObject(multiLineQname,null); 
								int linec=iomMultiPolygon.getattrvaluecount(Wkb2iox.ATTR_POLYLINE);
								for(int linei=0;linei<linec;linei++){
									IomObject iomPolygon=iomMultiPolygon.getattrobj(Wkb2iox.ATTR_POLYLINE,linei);
									IomObject iomChbaseSurfaceStructure=iomChbaseMultiLine.addattrobj(attrMapping.getBagOfLinesAttrName(), lineStructureQname);
									iomChbaseSurfaceStructure.addattrobj(attrMapping.getLineAttrName(), iomPolygon);
								}
								iomObj.addattrobj(attrName,iomChbaseMultiLine);
							}catch(ConverterException ex){
								EhiLogger.logError("Object "+sqlid+": failed to convert polyline",ex);
							}	
						}
					}else if(TrafoConfigNames.MULTIPOINT_TRAFO_COALESCE.equals(trafoConfig.getAttrConfig(attr, TrafoConfigNames.MULTIPOINT_TRAFO))){
						 MultiPointMapping attrMapping=multiPointAttrs.getMapping(attr);
						Table multiPointType = ((CompositionType) type).getComponentType();
						Table pointStructureType=((CompositionType) ((AttributeDef) multiPointType.getElement(AttributeDef.class, attrMapping.getBagOfPointsAttrName())).getDomain()).getComponentType();
						String multiPointQname=multiPointType.getScopedName(null);
						String pointStructureQname=pointStructureType.getScopedName(null);
						CoordType coord=((CoordType) ((AttributeDef) pointStructureType.getElement(AttributeDef.class,attrMapping.getPointAttrName())).getDomainResolvingAliases());
						boolean is3D=coord.getDimensions().length==3;
						Object geomobj=rs.getObject(valuei);
						valuei++;
						if(!rs.wasNull()){
							try{
								IomObject iomMultiPoint=geomConv.toIomMultiCoord(geomobj,sqlAttrName,is3D);
								IomObject iomChbaseMultiPoint=new Iom_jObject(multiPointQname,null); 
								int pointc=iomMultiPoint.getattrvaluecount(Wkb2iox.ATTR_COORD);
								for(int pointi=0;pointi<pointc;pointi++){
									IomObject iomPoint=iomMultiPoint.getattrobj(Wkb2iox.ATTR_COORD,pointi);
									IomObject iomChbasePointStructure=iomChbaseMultiPoint.addattrobj(attrMapping.getBagOfPointsAttrName(), pointStructureQname);
									iomChbasePointStructure.addattrobj(attrMapping.getPointAttrName(), iomPoint);
								}
								iomObj.addattrobj(attrName,iomChbaseMultiPoint);
							}catch(ConverterException ex){
								EhiLogger.logError("Object "+sqlid+": failed to convert coord",ex);
							}	
						}
					}else if(TrafoConfigNames.ARRAY_TRAFO_COALESCE.equals(trafoConfig.getAttrConfig(attr, TrafoConfigNames.ARRAY_TRAFO))){
						 ArrayMapping attrMapping=arrayAttrs.getMapping(attr);
						Object dbValue=rs.getObject(valuei);
						valuei++;
						if(!rs.wasNull()){
							try{
								Table valueStructType = ((CompositionType) type).getComponentType();
								String valueStructQname=valueStructType.getScopedName(null);
								String iomArray[]=geomConv.toIomArray(attrMapping.getValueAttr(),dbValue,enumTypes);
								for(int elei=0;elei<iomArray.length;elei++){
									IomObject iomValueStruct=new Iom_jObject(valueStructQname,null); 
									iomValueStruct.setattrvalue(attrMapping.getValueAttr().getName(), iomArray[elei]);
									iomObj.addattrobj(attrName, iomValueStruct);
								}
							}catch(ConverterException ex){
								EhiLogger.logError("Object "+sqlid+": failed to convert array",ex);
							}	
						}
					}else if(TrafoConfigNames.MULTILINGUAL_TRAFO_EXPAND.equals(trafoConfig.getAttrConfig(attr, TrafoConfigNames.MULTILINGUAL_TRAFO))){
						IomObject iomMulti=null;
						Table multilingualTextType = ((CompositionType) type).getComponentType();
						String multilingualTextQname=multilingualTextType.getScopedName(null);
						String localizedTextQname=((CompositionType) ((AttributeDef) multilingualTextType.getAttributes().next()).getDomain()).getComponentType().getScopedName(null);
						for(String sfx:DbNames.MULTILINGUAL_TXT_COL_SUFFIXS){
							String value=rs.getString(valuei);
							valuei++;
							if(!rs.wasNull()){
								if(iomMulti==null){
									iomMulti=new Iom_jObject(multilingualTextQname, null);
								}
								IomObject iomTxt=iomMulti.addattrobj(IliNames.CHBASE1_LOCALISEDTEXT,localizedTextQname);
								if(sfx.length()==0) {
	                                iomTxt.setattrundefined(IliNames.CHBASE1_LOCALISEDTEXT_LANGUAGE);
								}else {
	                                iomTxt.setattrvalue(IliNames.CHBASE1_LOCALISEDTEXT_LANGUAGE,sfx.substring(LEN_LANG_PREFIX));
								}
								iomTxt.setattrvalue(IliNames.CHBASE1_LOCALISEDTEXT_TEXT,value);
							}
						}
						if(iomMulti!=null){
							iomObj.addattrobj(attrName, iomMulti);
						}
					}else{
						// enque iomObj as parent
						structQueue.add(new StructWrapper(sqlid,attr,iomObj,table));
					}
				}else if (type instanceof PolylineType){
					Object geomobj=rs.getObject(valuei);
					valuei++;
					if(!rs.wasNull()){
						try{
						boolean is3D=((CoordType)((PolylineType)type).getControlPointDomain().getType()).getDimensions().length==3;
						IomObject polyline=geomConv.toIomPolyline(geomobj,sqlAttrName,is3D);
						iomObj.addattrobj(attrName,polyline);
						}catch(ConverterException ex){
							EhiLogger.logError("Object "+sqlid+": failed to convert polyline",ex);
						}	
					}
				 }else if(type instanceof SurfaceOrAreaType){
					 if(createItfLineTables){
					 }else{
							Object geomobj=rs.getObject(valuei);
							valuei++;
							if(!rs.wasNull()){
								try{
									boolean is3D=((CoordType)((SurfaceOrAreaType)type).getControlPointDomain().getType()).getDimensions().length==3;
									IomObject surface=geomConv.toIomSurface(geomobj,sqlAttrName,is3D);
									iomObj.addattrobj(attrName,surface);
								}catch(ConverterException ex){
									EhiLogger.logError("Object "+sqlid+": failed to convert surface/area",ex);
								}	
							}
					 }
					 if(createItfAreaRef){
						 if(type instanceof AreaType){
								Object geomobj=rs.getObject(valuei);
								valuei++;
								if(!rs.wasNull()){
									try{
										boolean is3D=false;
										IomObject coord=geomConv.toIomCoord(geomobj,sqlAttrName,is3D);
										iomObj.addattrobj(attrName,coord);
									}catch(ConverterException ex){
										EhiLogger.logError("Object "+sqlid+": failed to convert coord",ex);
									}
								}
						 }
					 }
				 }else if(type instanceof CoordType){
					Object geomobj=rs.getObject(valuei);
					valuei++;
					if(!rs.wasNull()){
						try{
							boolean is3D=((CoordType)type).getDimensions().length==3;
							IomObject coord=geomConv.toIomCoord(geomobj,sqlAttrName,is3D);
							iomObj.addattrobj(attrName,coord);
						}catch(ConverterException ex){
							EhiLogger.logError("Object "+sqlid+": failed to convert coord",ex);
						}
					}
				}else if(type instanceof EnumerationType){
					if(createEnumColAsItfCode){
						int value=rs.getInt(valuei);
						valuei++;
						if(!rs.wasNull()){
							iomObj.setattrvalue(attrName,mapItfCode2XtfCode((EnumerationType)type, value));
						}
					}else{
						String value=rs.getString(valuei);
						valuei++;
						if(!rs.wasNull()){
							iomObj.setattrvalue(attrName,value);
						}
						
					}
				}else if(type instanceof ReferenceType){
					ArrayList<ViewableWrapper> targetTables = getTargetTables(((ReferenceType)type).getReferred());
					boolean refAlreadyDefined=false;
					for(ViewableWrapper targetTable:targetTables)
					{
						long value=rs.getLong(valuei);
						valuei++;
						if(!rs.wasNull()){
							if(refAlreadyDefined){
								sqlAttrName=ili2sqlName.mapIliAttributeDef(attr,table.getSqlTablename(),targetTable.getSqlTablename(),targetTables.size()>1);
								EhiLogger.logAdaption("Table "+table.getSqlTablename()+"(id "+sqlid+") more than one value for refattr "+attrName+"; value of "+sqlAttrName+" ignored");
							}else{
								IomObject ref=iomObj.addattrobj(attrName,"REF");
								mapSqlid2Xtfid(fixref,value,ref,((ReferenceType)type).getReferred());
								refAlreadyDefined=true;
							}
						}
					}
				}else if(type instanceof BlackboxType){
					if(((BlackboxType)type).getKind()==BlackboxType.eXML){
						Object obj=rs.getObject(valuei);
						valuei++;
						if(!rs.wasNull()){
							try {
								iomObj.setattrvalue(attrName,geomConv.toIomXml(obj));
							} catch (ConverterException ex) {
								EhiLogger.logError("Object "+sqlid+": failed to convert blackbox xml",ex);
							}
						}
					}else{
						Object obj=rs.getObject(valuei);
						valuei++;
						if(!rs.wasNull()){
							try {
								iomObj.setattrvalue(attrName,geomConv.toIomBlob(obj));
							} catch (ConverterException ex) {
								EhiLogger.logError("Object "+sqlid+": failed to convert blackbox binary",ex);
							}
						}
					}
				}else{
					String value=rs.getString(valuei);
					valuei++;
					if(!rs.wasNull()){
						iomObj.setattrvalue(attrName,value);
					}
				}
			   }
			}
		return valuei;
	}
	private String mapSqlid2Xtfid(FixIomObjectRefs fixref, long sqlid,IomObject refobj,Viewable targetClass) {
		if(sqlid2xtfid.containsSqlid(sqlid)){
			refobj.setobjectrefoid(sqlid2xtfid.getXtfid(sqlid));
		}else{
			fixref.addFix(refobj,sqlid,targetClass);
		}
		return null;
	}

}
