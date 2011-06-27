/*
 * Copyright 2005 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.drools.guvnor.client.modeldriven.ui;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.drools.guvnor.client.common.DirtyableComposite;
import org.drools.guvnor.client.common.SmallLabel;
import org.drools.guvnor.client.common.ValueChanged;
import org.drools.guvnor.client.messages.Constants;
import org.drools.ide.common.client.modeldriven.SuggestionCompletionEngine;
import org.drools.ide.common.client.modeldriven.brl.DSLSentence;
import org.drools.ide.common.client.modeldriven.ui.ConstraintValueEditorHelper;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.dom.client.ChangeEvent;
import com.google.gwt.event.dom.client.ChangeHandler;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.HTML;
import com.google.gwt.user.client.ui.HorizontalPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.ListBox;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.VerticalPanel;
import com.google.gwt.user.client.ui.Widget;

/**
 * This displays a widget to edit a DSL sentence.
 */
public class DSLSentenceWidget extends RuleModellerWidget {

    private static final String ENUM_TAG    = "ENUM";
    private static final String DATE_TAG    = "DATE";
    private static final String BOOLEAN_TAG = "BOOLEAN";
    private final List<Widget>  widgets;
    private final DSLSentence   sentence;
    private final VerticalPanel layout;
    private HorizontalPanel     currentRow;
    private boolean             readOnly;

    public DSLSentenceWidget(RuleModeller modeller,
                             DSLSentence sentence) {
        this( modeller,
              sentence,
              null );
    }

    public DSLSentenceWidget(RuleModeller modeller,
                             DSLSentence sentence,
                             Boolean readOnly) {
        super( modeller );
        widgets = new ArrayList<Widget>();
        this.sentence = sentence;

        if ( readOnly == null ) {
            this.readOnly = false;
        } else {
            this.readOnly = readOnly;
        }

        this.layout = new VerticalPanel();
        this.currentRow = new HorizontalPanel();
        this.layout.add( currentRow );
        this.layout.setCellWidth( currentRow,
                                  "100%" );
        this.layout.setWidth( "100%" );

        if ( this.readOnly ) {
            this.layout.addStyleName( "editor-disabled-widget" );
        }

        init();
    }

    private void init() {
        makeWidgets( this.sentence );
        initWidget( this.layout );
    }

    /**
     * This will take a DSL line item, and split it into widget thingamies for
     * displaying. One day, if this is too complex, this will have to be done on
     * the server side.
     */
    public void makeWidgets(DSLSentence sentence) {

        String dslDefinition = sentence.getDefinition();
        List<String> dslValues = sentence.getValues();
        int index = 0;

        int startVariable = dslDefinition.indexOf( "{" );
        List<Widget> lineWidgets = new ArrayList<Widget>();

        boolean firstOneIsBracket = (dslDefinition.indexOf( "{" ) == 0);

        String startLabel = "";
        if ( startVariable > 0 ) {
            startLabel = dslDefinition.substring( 0,
                                                  startVariable );
        } else if ( !firstOneIsBracket ) {
            // There are no curly brackets in the text. Just print it
            startLabel = dslDefinition;
        }

        Widget label = getLabel( startLabel );
        lineWidgets.add( label );

        while ( startVariable > 0 || firstOneIsBracket ) {
            firstOneIsBracket = false;

            int endVariable = dslDefinition.indexOf( "}",
                                                     startVariable );
            String currVariable = dslDefinition.substring( startVariable + 1,
                                                           endVariable );

            String value = dslValues.get( index );
            Widget varWidget = processVariable( currVariable,
                                                value );
            lineWidgets.add( varWidget );
            index++;

            // Parse out the next label between variables
            startVariable = dslDefinition.indexOf( "{",
                                                   endVariable );
            String lbl;
            if ( startVariable > 0 ) {
                lbl = dslDefinition.substring( endVariable + 1,
                                               startVariable );
            } else {
                lbl = dslDefinition.substring( endVariable + 1,
                                               dslDefinition.length() );
            }

            if ( lbl.indexOf( "\\n" ) > -1 ) {
                String[] lines = lbl.split( "\\\\n" );
                for ( int i = 0; i < lines.length; i++ ) {
                    lineWidgets.add( new NewLine() );
                    lineWidgets.add( getLabel( lines[i] ) );
                }
            } else {
                Widget currLabel = getLabel( lbl );
                lineWidgets.add( currLabel );
            }

        }

        for ( Widget widg : lineWidgets ) {
            addWidget( widg );
        }
        updateSentence();
    }

    class NewLine extends Widget {
    }

    public Widget processVariable(String currVariable,
                                  String value) {

        Widget result = null;
        // Formats are: 
        // <varName>:ENUM:<Field.type>
        // <varName>:DATE:<dateFormat>
        // <varName>:BOOLEAN:[checked | unchecked] <-initial value
        // Note: <varName> is no longer used; values are stored in DSLSentence.values()

        if ( currVariable.contains( ":" ) ) {
            if ( currVariable.contains( ":" + ENUM_TAG + ":" ) ) {
                result = getEnumDropdown( currVariable,
                                          value );
            } else if ( currVariable.contains( ":" + DATE_TAG + ":" ) ) {
                result = getDateSelector( currVariable,
                                          value );
            } else if ( currVariable.contains( ":" + BOOLEAN_TAG + ":" ) ) {
                result = getCheckbox( currVariable,
                                      value );
            } else {
                String regex = currVariable.substring( currVariable.indexOf( ":" ) + 1,
                                                       currVariable.length() );
                result = getBox( value,
                                 regex );
            }
        } else {
            result = getBox( value,
                             "" );
        }

        return result;
    }

    public Widget getEnumDropdown(String variableDef,
                                  String value) {

        Widget resultWidget = new DSLDropDown( variableDef,
                                               value );
        return resultWidget;
    }

    public Widget getBox(String variableDef,
                         String regex) {

        FieldEditor currentBox = new FieldEditor();
        currentBox.setVisibleLength( variableDef.length() + 1 );
        currentBox.setText( variableDef );
        currentBox.setRestriction( regex );

        return currentBox;
    }

    public Widget getCheckbox(String variableDef,
                              String value) {
        return new DSLCheckBox( variableDef,
                                value );
    }

    public Widget getDateSelector(String variableDef,
                                  String value) {
        String[] parts = variableDef.split( ":" + DATE_TAG + ":" );
        return new DSLDateSelector( value,
                                    parts[1] );
    }

    public Widget getLabel(String labelDef) {
        Label label = new SmallLabel();
        label.setText( labelDef );

        return label;
    }

    private void addWidget(Widget currentBox) {
        if ( currentBox instanceof NewLine ) {
            currentRow = new HorizontalPanel();
            layout.add( currentRow );
            layout.setCellWidth( currentRow,
                                 "100%" );
        } else {
            currentRow.add( currentBox );
        }
        widgets.add( currentBox );
    }

    /**
     * This will go through the widgets and extract the values
     */
    protected void updateSentence() {
        List<String> dslValues = new ArrayList<String>();
        for ( Iterator<Widget> iter = widgets.iterator(); iter.hasNext(); ) {
            Widget wid = iter.next();
            if ( wid instanceof FieldEditor ) {
                FieldEditor editor = (FieldEditor) wid;
                dslValues.add( editor.getText().trim() );
            } else if ( wid instanceof DSLDropDown ) {
                DSLDropDown drop = (DSLDropDown) wid;
                ListBox box = drop.getListBox();
                dslValues.add( box.getValue( box.getSelectedIndex() ) );

            } else if ( wid instanceof DSLCheckBox ) {
                DSLCheckBox check = (DSLCheckBox) wid;
                dslValues.add( check.getCheckedValue() );

            } else if ( wid instanceof DSLDateSelector ) {
                DSLDateSelector dateSel = (DSLDateSelector) wid;
                String dateString = dateSel.getDateString();
                dslValues.add( dateString );

            }
        }
        this.setModified( true );
        this.sentence.setValues( dslValues );
    }

    class FieldEditor extends DirtyableComposite {

        private TextBox         box;
        private HorizontalPanel panel     = new HorizontalPanel();
        private String          oldValue  = "";
        private String          regex     = "";
        private Constants       constants = ((Constants) GWT.create( Constants.class ));

        public FieldEditor() {
            box = new TextBox();
            // box.setStyleName( "dsl-field-TextBox" );

            panel.add( new HTML( "&nbsp;" ) );
            panel.add( box );
            panel.add( new HTML( "&nbsp;" ) );
            box.addChangeHandler( new ChangeHandler() {

                public void onChange(ChangeEvent event) {
                    TextBox otherBox = (TextBox) event.getSource();

                    if ( !regex.equals( "" ) && !otherBox.getText().matches( regex ) ) {
                        Window.alert( constants.TheValue0IsNotValidForThisField( otherBox.getText() ) );
                        box.setText( oldValue );
                    } else {
                        oldValue = otherBox.getText();
                        updateSentence();
                        makeDirty();
                    }
                }
            } );

            initWidget( panel );
        }

        public void setText(String t) {
            box.setText( t );
        }

        public void setVisibleLength(int l) {
            box.setVisibleLength( l );
        }

        public String getText() {
            return box.getText();
        }

        public void setRestriction(String regex) {
            this.regex = regex;
        }

        public String getRestriction() {
            return this.regex;
        }

        public boolean isValid() {
            boolean result = true;
            if ( !regex.equals( "" ) ) result = this.box.getText().matches( this.regex );
            return result;
        }
    }

    class DSLDropDown extends DirtyableComposite {
        final SuggestionCompletionEngine completions  = getModeller().getSuggestionCompletions();
        ListBox                          resultWidget = null;
        // Format for the dropdown def is <varName>:<type>:<Fact.field>
        private String                   type         = "";
        private String                   factAndField = "";

        public DSLDropDown(String variableDef,
                           String value) {
            int firstIndex = variableDef.indexOf( ":" );
            int lastIndex = variableDef.lastIndexOf( ":" );
            type = variableDef.substring( firstIndex + 1,
                                          lastIndex );
            factAndField = variableDef.substring( lastIndex + 1,
                                                  variableDef.length() );

            int dotIndex = factAndField.indexOf( "." );
            String type = factAndField.substring( 0,
                                                  dotIndex );
            String field = factAndField.substring( dotIndex + 1,
                                                   factAndField.length() );

            String[] data = completions.getEnumValues( type,
                                                       field );
            ListBox list = new ListBox();

            if ( data != null ) {
                int selected = -1;
                for ( int i = 0; i < data.length; i++ ) {
                    String realValue = data[i];
                    String display = data[i];
                    if ( data[i].indexOf( '=' ) > -1 ) {
                        String[] vs = ConstraintValueEditorHelper.splitValue( data[i] );
                        realValue = vs[0];
                        display = vs[1];
                    }
                    if ( value.equals( realValue ) ) {
                        selected = i;
                    }
                    list.addItem( display,
                                  realValue );
                }
                if ( selected >= 0 ) list.setSelectedIndex( selected );
            }
            list.addChangeHandler( new ChangeHandler() {

                public void onChange(ChangeEvent event) {
                    updateSentence();
                    makeDirty();
                }
            } );

            initWidget( list );
            resultWidget = list;
        }

        public ListBox getListBox() {
            return resultWidget;
        }

        public void setListBox(ListBox resultWidget) {
            this.resultWidget = resultWidget;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getFactAndField() {
            return factAndField;
        }

        public void setFactAndField(String factAndField) {
            this.factAndField = factAndField;
        }
    }

    class DSLCheckBox extends Composite {
        ListBox resultWidget = null;

        public DSLCheckBox(String variableDef,
                           String value) {

            resultWidget = new ListBox();
            resultWidget.addItem( "true" );
            resultWidget.addItem( "false" );

            if ( value.equalsIgnoreCase( "true" ) ) {
                resultWidget.setSelectedIndex( 0 );
            } else {
                resultWidget.setSelectedIndex( 1 );
            }

            resultWidget.addClickHandler( new ClickHandler() {

                public void onClick(ClickEvent event) {
                    updateSentence();

                }
            } );

            resultWidget.setVisible( true );
            initWidget( resultWidget );
        }

        public ListBox getListBox() {
            return resultWidget;
        }

        public void setListBox(ListBox resultWidget) {
            this.resultWidget = resultWidget;
        }

        public String getType() {
            return BOOLEAN_TAG;
        }

        public String getCheckedValue() {
            return this.resultWidget.getSelectedIndex() == 0 ? "true" : "false";

        }
    }

    class DSLDateSelector extends DatePickerLabel {

        public DSLDateSelector(String selectedDate,
                               String dateFormat) {
            super( selectedDate,
                   dateFormat );

            addValueChanged( new ValueChanged() {
                public void valueChanged(String newValue) {
                    updateSentence();
                }
            } );
        }

        public String getType() {
            return DATE_TAG;
        }
    }

    @Override
    public boolean isReadOnly() {
        return this.readOnly;
    }
}
