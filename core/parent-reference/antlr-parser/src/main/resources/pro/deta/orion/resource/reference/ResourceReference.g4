grammar ResourceReference;

@header {
package pro.deta.orion.resource.reference.generated;
}

reference
    : addressReference EOF
    | interpolation EOF
    ;

interpolation
    : interpolationPart+
    ;

interpolationPart
    : bracedReference
    | shorthandVariable
    | literal
    ;

bracedReference
    : DOLLAR_LBRACE bracedBody RBRACE
    ;

bracedBody
    : documentReference
    | variableExpansion
    | addressReference
    ;

documentReference
    : documentFormat COLON documentSource documentPath
    ;

documentFormat
    : YAML
    | TOML
    | XML
    ;

documentSource
    : bracedReference
    | shorthandVariable
    ;

documentPath
    : SLASH documentPathSegment (SLASH documentPathSegment)*
    ;

documentPathSegment
    : documentPathAtom+
    ;

documentPathAtom
    : identifier
    | TEXT
    | COLON
    | QUESTION
    | DASH
    | PLUS
    | DOT
    ;

variableExpansion
    : variableName parameterExpansion?
    ;

variableName
    : identifier (DOT identifier)*
    ;

parameterExpansion
    : COLON DASH word
    | DASH word
    | COLON QUESTION word
    | QUESTION word
    ;

word
    : interpolationPart*
    ;

addressReference
    : scheme COLON addressBody
    ;

scheme
    : identifier ((PLUS | DOT | DASH) identifier)*
    ;

addressBody
    : interpolationPart*
    ;

shorthandVariable
    : DOLLAR identifier
    ;

literal
    : literalAtom+
    ;

literalAtom
    : identifier
    | TEXT
    | COLON
    | QUESTION
    | DASH
    | SLASH
    | PLUS
    | DOT
    ;

identifier
    : SIMPLE_NAME
    | YAML
    | TOML
    | XML
    ;

YAML          : 'yaml';
TOML          : 'toml';
XML           : 'xml';
DOLLAR_LBRACE : '${';
DOLLAR        : '$';
RBRACE        : '}';
COLON         : ':';
QUESTION      : '?';
DASH          : '-';
SLASH         : '/';
PLUS          : '+';
DOT           : '.';
SIMPLE_NAME   : [A-Za-z_] [A-Za-z0-9_]*;
TEXT          : ~[$}:?\\/+.\r\n-]+;
