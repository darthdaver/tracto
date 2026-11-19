package star.tracto.lsp.antlr4;

import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.atn.*;
import star.tracto.lsp.antlr4.antlr4Class.JavaLexer;
import star.tracto.lsp.antlr4.antlr4Class.JavaParser;


import java.util.*;

/**
 * Riconosce prefissi della grammatica senza inserire, cancellare o recuperare token.
 * Segue l'ATN mantenendo lo stack delle chiamate alle regole e la precedenza.
 * Un errore del parser tradizionale non deve diventare una continuazione suggerita.
 */
public final class PrefixSyntax {

    private static final String LESS_THAN = "<";
    private static final String SPACE_LESS_THAN = " <";
    private static final String OPEN_BLOCK_SPACE = "{ ";
    private static final String PREFIX_SYNTAX_LIMIT = "Analisi sintattica troppo complessa; nessun token accettato.";
    private static final String ERROR = "ERROR";

    private static final ATN ATN = JavaParser._ATN;
    private static final int MAX_CONFIGURATIONS = 100_000;
    private record Return(int state, int precedence) {}
    private record Configuration(int state, List<Return> returns, int precedence) {}
    private record Analysis(Set<Configuration> configurations) {}

    // Cache limitata e per thread: non condivide lo stato di generazioni concorrenti.
    private static final ThreadLocal<Map<String, Analysis>> CACHE = ThreadLocal.withInitial(() ->
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Analysis> eldest) {
                    return size() > 16;
                }
            });

    /** Impedisce di istanziare questa classe di utilità. */
    private PrefixSyntax() {}

    /** Verifica se il frammento può ancora essere completato secondo la grammatica Java. */
    public static boolean isViable(String snippet) {
        return !analyze(snippet).configurations().isEmpty();
    }

    /** Ricava i token ammessi dopo il frammento corrente. */
    static List<String> candidates(String snippet) {
        Set<Integer> types = new TreeSet<>();
        for (Configuration config : analyze(snippet).configurations()) {
            for (Transition transition : ATN.states.get(config.state()).getTransitions()) {
                if (transition.isEpsilon()) continue;
                for (int type = Token.MIN_USER_TOKEN_TYPE; type <= ATN.maxTokenType; type++) {
                    if (transition.matches(type, Token.MIN_USER_TOKEN_TYPE, ATN.maxTokenType)) types.add(type);
                }
            }
        }
        List<String> names = new ArrayList<>();
        for (int type : types) {
            String literal = JavaParser.VOCABULARY.getLiteralName(type);
            String name = literal == null ? JavaParser.VOCABULARY.getSymbolicName(type)
                    : literal.substring(1, literal.length() - 1);
            // La grammatica ANTLR rappresenta << come due LT; nel testo Java devono
            // essere adiacenti. Il generatore separa i token con spazi.
            if (name != null && !(name.equals(LESS_THAN) && !isViable(snippet + SPACE_LESS_THAN))) names.add(name);
        }
        return names;
    }

    /** Fa avanzare l’automa della grammatica sui token del frammento. */
    private static Analysis analyze(String snippet) {
        Analysis cached = CACHE.get().get(snippet);
        if (cached != null) return cached;
        boolean[] lexicalError = {false};
        JavaLexer lexer = new JavaLexer(CharStreams.fromString(OPEN_BLOCK_SPACE + snippet));
        lexer.removeErrorListeners();
        lexer.addErrorListener(new BaseErrorListener() {
            @Override public void syntaxError(Recognizer<?, ?> recognizer, Object symbol, int line,
                                              int column, String message, RecognitionException error) {
                lexicalError[0] = true;
            }
        });
        Set<Configuration> current = closure(Set.of(new Configuration(
                ATN.ruleToStartState[JavaParser.RULE_block].stateNumber, List.of(), 0)));
        Token previous = null;
        for (Token token = lexer.nextToken(); token.getType() != Token.EOF; token = lexer.nextToken()) {
            if (token.getChannel() != Token.DEFAULT_CHANNEL) continue;
            if (previous != null && previous.getType() == JavaLexer.LT && token.getType() == JavaLexer.LT
                    && token.getStartIndex() > previous.getStopIndex() + 1) {
                current = Set.of();
                break;
            }
            previous = token;
            Set<Configuration> next = new HashSet<>();
            for (Configuration config : current) {
                for (Transition transition : ATN.states.get(config.state()).getTransitions()) {
                    if (!transition.isEpsilon() && transition.matches(token.getType(), Token.MIN_USER_TOKEN_TYPE, ATN.maxTokenType)) {
                        next.add(new Configuration(transition.target.stateNumber, config.returns(), config.precedence()));
                    }
                }
            }
            current = closure(next);
            if (current.isEmpty()) break;
        }
        Analysis result = new Analysis(lexicalError[0] ? Set.of() : Set.copyOf(current));
        CACHE.get().put(snippet, result);
        return result;
    }

    /** Visita le transizioni che non consumano token. */
    private static Set<Configuration> closure(Set<Configuration> start) {
        Set<Configuration> visited = new HashSet<>(), terminals = new HashSet<>();
        Deque<Configuration> pending = new ArrayDeque<>(start);
        while (!pending.isEmpty()) {
            Configuration config = pending.removeFirst();
            if (!visited.add(config)) continue;
            if (visited.size() > MAX_CONFIGURATIONS || config.returns().size() > 256) {
                throw new IllegalStateException(PREFIX_SYNTAX_LIMIT);
            }
            ATNState state = ATN.states.get(config.state());
            if (state instanceof RuleStopState) {
                if (config.returns().isEmpty()) terminals.add(config);
                else {
                    Return destination = config.returns().get(config.returns().size() - 1);
                    pending.add(new Configuration(destination.state(),
                            config.returns().subList(0, config.returns().size() - 1), destination.precedence()));
                }
                continue;
            }
            for (Transition transition : state.getTransitions()) {
                if (transition instanceof RuleTransition rule) {
                    List<Return> returns = new ArrayList<>(config.returns());
                    returns.add(new Return(rule.followState.stateNumber, config.precedence()));
                    int precedence = ((RuleStartState) rule.target).isLeftRecursiveRule ? rule.precedence : config.precedence();
                    pending.add(new Configuration(rule.target.stateNumber, List.copyOf(returns), precedence));
                } else if (transition instanceof PrecedencePredicateTransition predicate) {
                    if (predicate.precedence >= config.precedence()) {
                        pending.add(new Configuration(transition.target.stateNumber, config.returns(), config.precedence()));
                    }
                } else if (transition.isEpsilon()) {
                    pending.add(new Configuration(transition.target.stateNumber, config.returns(), config.precedence()));
                } else terminals.add(config);
            }
        }
        return terminals;
    }
}

